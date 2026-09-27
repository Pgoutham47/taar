package com.taar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream

/**
 * The photo behind each Board Map, one per board, kept in the app's private
 * storage. Nothing is uploaded and nothing goes to the gallery.
 *
 * Every photo is stored upright and at most [MAX_SIDE] pixels on its long side.
 * A camera JPEG usually stores its rotation as a tag rather than in the pixels;
 * applying it once, here, means the dot positions are always relative to the
 * picture as the technician saw it.
 */
class BoardPhotos(private val context: Context) {

    private val dir = File(context.filesDir, "taar/boardphotos").apply { mkdirs() }

    private fun photo(installationId: String) = File(dir, "$installationId.jpg")

    /** The camera app writes here first, so a cancelled shot never replaces the old photo. */
    private fun cameraFile(installationId: String) = File(dir, "$installationId.camera.jpg")

    fun has(installationId: String): Boolean = photo(installationId).isFile

    /** A URI the camera app can write the new photo to. */
    fun cameraTarget(installationId: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", cameraFile(installationId))

    /** After the camera returns: keeps its photo as this board's. Blocking; call off the main thread. */
    fun acceptCamera(installationId: String): Boolean {
        val shot = cameraFile(installationId)
        return try {
            shot.isFile && shot.length() > 0 && store({ shot.inputStream() }, photo(installationId))
        } finally {
            shot.delete()
        }
    }

    /** A photo picked from the gallery. Blocking; call off the main thread. */
    fun importFrom(uri: Uri, installationId: String): Boolean =
        store({ context.contentResolver.openInputStream(uri) }, photo(installationId))

    /** Blocking; call off the main thread. */
    fun load(installationId: String): Bitmap? =
        photo(installationId).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    private fun store(open: () -> InputStream?, target: File): Boolean = runCatching {
        // Decoding bounds only always returns null; the size arrives in the options.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (open() ?: return false).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false

        val degrees = open()?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(degrees) }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    private companion object {
        /** Enough to read a breaker label on a phone screen; small enough to decode instantly. */
        const val MAX_SIDE = 1600
    }
}
