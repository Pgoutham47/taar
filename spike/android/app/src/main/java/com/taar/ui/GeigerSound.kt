package com.taar.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.exp
import kotlin.random.Random

/**
 * The click and the tick of Geiger mode.
 *
 * The click is generated rather than shipped: a few milliseconds of decaying
 * noise, which is what a Geiger counter's click is. It is written once to the
 * cache as a WAV and played through SoundPool, which starts a short sound with
 * far less delay than MediaPlayer. Nothing here records; the microphone is not
 * used by Geiger mode at all.
 */
class GeigerSound(context: Context) : AutoCloseable {

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    @Volatile private var ready = false
    private val clickId: Int

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val tick: VibrationEffect? = when {
        vibrator?.hasVibrator() != true -> null
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        else -> VibrationEffect.createOneShot(8, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    init {
        val file = File(context.cacheDir, "geiger-click.wav")
        if (!file.exists()) writeClick(file)
        pool.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        clickId = pool.load(file.path, 1)
    }

    fun click(sound: Boolean, vibrate: Boolean) {
        if (sound && ready) pool.play(clickId, 1f, 1f, 1, 0, 1f)
        if (vibrate) tick?.let { vibrator?.vibrate(it) }
    }

    override fun close() {
        pool.release()
        vibrator?.cancel()
    }

    private fun writeClick(file: File) {
        val rate = 44_100
        val n = (rate * 0.006).toInt()
        val rnd = Random(7)
        val pcm = ShortArray(n) { i ->
            // A sharp edge, then a short burst of noise dying away in about 2 ms.
            val v = (if (i < 4) 1.0 else rnd.nextDouble(-1.0, 1.0)) * exp(-i / 90.0) * 0.9
            (v * Short.MAX_VALUE).toInt().toShort()
        }
        val tmp = File(file.parentFile, "geiger-click.tmp")
        RandomAccessFile(tmp, "rw").use { f ->
            f.setLength(0)
            val bytes = n * 2
            f.writeBytes("RIFF"); f.writeIntLe(36 + bytes); f.writeBytes("WAVE")
            f.writeBytes("fmt "); f.writeIntLe(16); f.writeShortLe(1); f.writeShortLe(1)
            f.writeIntLe(rate); f.writeIntLe(rate * 2); f.writeShortLe(2); f.writeShortLe(16)
            f.writeBytes("data"); f.writeIntLe(bytes)
            for (s in pcm) f.writeShortLe(s.toInt())
        }
        tmp.renameTo(file)
    }

    private fun RandomAccessFile.writeIntLe(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff); write((v shr 16) and 0xff); write((v shr 24) and 0xff)
    }

    private fun RandomAccessFile.writeShortLe(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff)
    }
}
