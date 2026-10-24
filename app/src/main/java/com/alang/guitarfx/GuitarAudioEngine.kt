package com.alang.guitarfx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.tanh

/**
 * Minimal PCM streaming prototype. Actual analogue iRig routing must be verified on-device.
 */
class GuitarAudioEngine(private val context: Context) {
    @Volatile var drive: Float = 2.0f
    @Volatile var isRunning: Boolean = false
        private set

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private val sampleRate = 44100

    @Synchronized
    fun start(): String {
        if (isRunning) return "Audio is already running"
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return "Microphone permission is required"
        return try {
            val minIn = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val minOut = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minIn <= 0 || minOut <= 0) return "This device does not support the requested PCM format"
            val bufferSize = maxOf(minIn, minOut, 2048) * 2
            val input = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            if (input.state != AudioRecord.STATE_INITIALIZED) {
                input.release()
                return "Audio input could not initialise. Check iRig/headset connection."
            }
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (output.state != AudioTrack.STATE_INITIALIZED) {
                input.release()
                output.release()
                return "Audio output could not initialise"
            }
            recorder = input
            track = output
            input.startRecording()
            output.play()
            isRunning = true
            worker = Thread({ processLoop(input, output) }, "GuitarFX-Audio").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            "Audio engine running — test input signal and wired output"
        } catch (e: SecurityException) {
            stop()
            "Microphone permission denied"
        } catch (e: Exception) {
            stop()
            "Audio start failed: " + e.javaClass.simpleName
        }
    }

    private fun processLoop(input: AudioRecord, output: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val pcm = ShortArray(256)
        while (isRunning && !Thread.currentThread().isInterrupted) {
            val count = input.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
            if (count <= 0) continue
            val gain = drive.coerceIn(1.0f, 8.0f)
            for (i in 0 until count) {
                val normalized = pcm[i] / 32768.0
                val shaped = tanh(normalized * gain) / tanh(gain.toDouble())
                pcm[i] = (shaped * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            }
            var written = 0
            while (written < count && isRunning) {
                val n = output.write(pcm, written, count - written, AudioTrack.WRITE_BLOCKING)
                if (n <= 0) break
                written += n
            }
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try { recorder?.stop() } catch (_: Exception) { }
        try { track?.pause(); track?.flush(); track?.stop() } catch (_: Exception) { }
        worker?.interrupt()
        try { worker?.join(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { recorder?.release() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        recorder = null
        track = null
        worker = null
    }
}
