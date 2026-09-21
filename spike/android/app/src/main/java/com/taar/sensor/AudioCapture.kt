package com.taar.sensor

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Microphone capture for arc detection.
 *
 * [MediaRecorder.AudioSource.UNPROCESSED] is requested rather than a voice source
 * because the arc signature lives at 4-16 kHz, and the processing on a voice path —
 * AGC, noise suppression, band limiting — is designed to remove exactly that kind of
 * content. Not every device honours the request, so [actualSource] reports what was
 * granted and the pre-check screen surfaces it. A fallback still works, because
 * baseline and measurement go through the same path, but it has to be said out loud
 * rather than assumed.
 */
class AudioCapture {

    data class Clip(
        val samples: DoubleArray,
        val sampleRateHz: Double,
        /** The source actually opened, which may not be the one requested. */
        val actualSource: Int,
        val unprocessedGranted: Boolean,
    )

    fun capture(durationSeconds: Double = 3.0, sampleRateHz: Int = 44_100): Clip? {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null

        var source = MediaRecorder.AudioSource.UNPROCESSED
        var record = tryOpen(source, sampleRateHz, minBuffer)
        var granted = true

        if (record == null) {
            granted = false
            source = MediaRecorder.AudioSource.MIC
            record = tryOpen(source, sampleRateHz, minBuffer) ?: return null
        }

        val total = (durationSeconds * sampleRateHz).toInt()
        val pcm = ShortArray(total)
        var read = 0

        return try {
            record.startRecording()
            while (read < total) {
                val n = record.read(pcm, read, total - read)
                if (n <= 0) break
                read += n
            }
            if (read < total / 2) return null

            // Normalise to [-1, 1] so thresholds do not depend on PCM scaling.
            val samples = DoubleArray(read) { pcm[it] / 32_768.0 }
            Clip(samples, sampleRateHz.toDouble(), source, granted)
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun tryOpen(source: Int, rateHz: Int, minBuffer: Int): AudioRecord? = runCatching {
        @Suppress("MissingPermission")
        val r = AudioRecord(
            source,
            rateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 4,
        )
        if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
    }.getOrNull()
}
