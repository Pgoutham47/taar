package com.taar.ar

import android.app.Activity
import android.opengl.GLES20
import android.util.Log
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.taar.domain.RoomMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The ARCore session behind Room 3D Scan: motion tracking, surface detection and
 * pinned points, all on the device.
 *
 * Runs as the renderer of a GLSurfaceView, because ARCore delivers each camera
 * frame through a GL texture. Everything the UI needs is published as a
 * [Snapshot] a few times a second rather than every frame, so Compose redraws
 * the overlay without being driven at 60 Hz.
 *
 * ARCore gives the room's surfaces and where the phone is. It knows nothing about
 * electricity; the measurements come from Taar's own pipeline, pinned to the
 * points this class returns.
 */
class ArRoom(private val activity: Activity) : GLSurfaceView.Renderer {

    class Snapshot(
        val tracking: TrackingState = TrackingState.STOPPED,
        val failure: TrackingFailureReason? = null,
        /** projection x view, column-major; null while not tracking. */
        val viewProjection: FloatArray? = null,
        val planes: List<RoomMap.Plane> = emptyList(),
        /** Current (refined) position of every pinned point. */
        val anchors: Map<Int, RoomMap.Vec3> = emptyMap(),
        /** Diagnostics shown on screen, so a black camera view explains itself. */
        val surfaceReady: Boolean = false,
        val frames: Long = 0,
        val error: String? = null,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private var session: Session? = null
    private val background = CameraBackground()
    private val anchors = ConcurrentHashMap<Int, Anchor>()
    private val pendingMark = AtomicReference<Pair<Int, CompletableDeferred<RoomMap.Vec3?>>?>(null)
    @Volatile private var textureSet = false
    private var width = 0
    private var height = 0
    private var lastPublish = 0L
    private var lastPlanes = 0L
    private var planes: List<RoomMap.Plane> = emptyList()
    @Volatile private var surfaceReady = false
    private var frames = 0L
    private var lastError: String? = null

    /**
     * Checks ARCore and opens a session. Returns a message for the technician when
     * that is not possible, or null on success. May start ARCore's own install
     * flow, after which the caller should try again.
     */
    fun open(): String? {
        if (session != null) return null
        return try {
            when (ArCoreApk.getInstance().requestInstall(activity, true)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED ->
                    return "Install \"Google Play Services for AR\" when asked, then come back and tap Start again."
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            session = Session(activity).also { s ->
                s.configure(Config(s).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                })
            }
            null
        } catch (e: UnavailableDeviceNotCompatibleException) {
            "This phone does not support ARCore, so Room 3D Scan is unavailable. Everything else in Taar works."
        } catch (e: UnavailableUserDeclinedInstallationException) {
            "Room 3D Scan needs \"Google Play Services for AR\". Tap Start again to install it."
        } catch (e: UnavailableArcoreNotInstalledException) {
            "Room 3D Scan needs \"Google Play Services for AR\". Tap Start again to install it."
        } catch (e: UnavailableApkTooOldException) {
            "Please update \"Google Play Services for AR\" from the Play Store."
        } catch (e: UnavailableSdkTooOldException) {
            "This version of Taar needs an update for ARCore."
        } catch (e: Exception) {
            "ARCore could not start: ${e.message ?: e::class.simpleName}"
        }
    }

    /** Returns a message if the camera could not be opened. */
    fun resume(): String? = try {
        session?.resume(); null
    } catch (e: Exception) {
        "The camera is in use by another app, or not available. Close it and try again."
    }

    fun pause() {
        runCatching { session?.pause() }
    }

    fun close() {
        anchors.values.forEach { runCatching { it.detach() } }
        anchors.clear()
        runCatching { session?.close() }
        session = null
        textureSet = false
    }

    /**
     * Pins the surface point under the centre of the screen and returns where it
     * is, or null when the crosshair is not on a detected surface.
     */
    suspend fun markCentre(id: Int): RoomMap.Vec3? {
        val done = CompletableDeferred<RoomMap.Vec3?>()
        pendingMark.getAndSet(id to done)?.second?.complete(null)
        return withTimeoutOrNull(MARK_TIMEOUT_MS) { done.await() }
    }

    fun unpin(id: Int) {
        anchors.remove(id)?.let { runCatching { it.detach() } }
    }

    // ---- GL thread ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.06f, 0.08f, 1f)
        background.create()
        textureSet = false
        surfaceReady = true
        publishDiagnostics()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        width = w
        height = h
        val rotation = ContextCompat.getDisplayOrDefault(activity).rotation
        session?.setDisplayGeometry(rotation, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: run { lastError = "no ARCore session"; publishDiagnostics(); return }
        val frame = try {
            if (!textureSet) {
                s.setCameraTextureName(background.textureId)
                s.setDisplayGeometry(ContextCompat.getDisplayOrDefault(activity).rotation, width, height)
                textureSet = true
            }
            s.update()
        } catch (e: Exception) {
            lastError = "${e::class.simpleName}: ${e.message ?: ""}".take(160)
            Log.e(TAG, "ARCore update failed", e)
            publishDiagnostics()
            return
        }
        frames++
        lastError = null
        background.draw(frame)
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING

        pendingMark.getAndSet(null)?.let { (id, done) ->
            done.complete(if (tracking) pin(frame, id) else null)
        }

        val now = System.currentTimeMillis()
        if (now - lastPublish < PUBLISH_MS) return
        lastPublish = now

        var vp: FloatArray? = null
        if (tracking) {
            val proj = FloatArray(16)
            val view = FloatArray(16)
            camera.getProjectionMatrix(proj, 0, 0.05f, 50f)
            camera.getViewMatrix(view, 0)
            vp = FloatArray(16).also { Matrix.multiplyMM(it, 0, proj, 0, view, 0) }
        }
        if (now - lastPlanes >= PLANES_MS) {
            lastPlanes = now
            planes = s.getAllTrackables(Plane::class.java)
                .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                .map { toRoomPlane(it) }
        }
        _snapshot.value = Snapshot(
            tracking = camera.trackingState,
            failure = if (tracking) null else camera.trackingFailureReason,
            viewProjection = vp,
            planes = planes,
            surfaceReady = surfaceReady,
            frames = frames,
            anchors = anchors.mapNotNull { (id, a) ->
                if (a.trackingState == TrackingState.STOPPED) null
                else id to a.pose.let { RoomMap.Vec3(it.tx().toDouble(), it.ty().toDouble(), it.tz().toDouble()) }
            }.toMap(),
        )
    }

    /** When no frame arrives, still tell the screen why, a few times a second. */
    private fun publishDiagnostics() {
        val now = System.currentTimeMillis()
        if (now - lastPublish < PUBLISH_MS) return
        lastPublish = now
        val old = _snapshot.value
        _snapshot.value = Snapshot(old.tracking, old.failure, old.viewProjection, old.planes, old.anchors,
            surfaceReady, frames, lastError)
    }

    /** The first hit on a detected surface, else on a tracked feature point with a surface direction. */
    private fun pin(frame: com.google.ar.core.Frame, id: Int): RoomMap.Vec3? {
        val hits = frame.hitTest(width / 2f, height / 2f)
        val hit = hits.firstOrNull { h ->
            val t = h.trackable
            (t is Plane && t.isPoseInPolygon(h.hitPose)) ||
                (t is Point && t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
        } ?: return null
        val anchor = hit.createAnchor()
        anchors.put(id, anchor)?.let { runCatching { it.detach() } }
        val p = anchor.pose
        return RoomMap.Vec3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble())
    }

    private fun toRoomPlane(plane: Plane): RoomMap.Plane {
        val poly = plane.polygon
        val centre = plane.centerPose
        val vertices = (0 until poly.limit() / 2).map { i ->
            val local = floatArrayOf(poly.get(i * 2), 0f, poly.get(i * 2 + 1))
            val w = centre.transformPoint(local)
            RoomMap.Vec3(w[0].toDouble(), w[1].toDouble(), w[2].toDouble())
        }
        return RoomMap.Plane(plane.hashCode().toString(), plane.type == Plane.Type.VERTICAL, vertices)
    }

    private companion object {
        const val PUBLISH_MS = 66L       // ~15 Hz for the overlay
        const val PLANES_MS = 500L
        const val MARK_TIMEOUT_MS = 2_000L
        const val TAG = "TaarAR"
    }
}
