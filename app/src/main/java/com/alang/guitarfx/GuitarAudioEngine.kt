package com.alang.guitarfx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Realtime PCM effects prototype. CAB IR currently uses a cabinet-style tone filter,
 * not a measured impulse-response convolution.
 */
class GuitarAudioEngine(private val context: Context) {
    @Volatile var drive: Float = 2.0f
    @Volatile var outputLevel: Float = 0.75f
    @Volatile var inputLevel: Float = 0.0f
        private set
    @Volatile var peakLevel: Float = 0.0f
        private set
    @Volatile var enabledEffects: Set<String> = setOf("OVERDRIVE")
    @Volatile var isRunning: Boolean = false
        private set

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private val sampleRate = 48000

    @Synchronized
    fun start(): String {
        if (isRunning) return "Audio is already running"
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return "Microphone permission is required"
        return try {
            val minIn = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val minOut = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minIn <= 0 || minOut <= 0) return "This device does not support the requested PCM format"
            // Keep buffers close to the platform minimum to reduce round-trip latency.
            val bufferSize = maxOf(minIn, minOut, 1024)
            val input = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            if (input.state != AudioRecord.STATE_INITIALIZED) {
                input.release()
                return "Audio input could not initialise. Check iRig/headset connection."
            }
            recorder = input
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                 .setBufferSizeInBytes(maxOf(minOut, 2048))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (output.state != AudioTrack.STATE_INITIALIZED) {
                input.release()
                output.release()
                return "Audio output could not initialise"
            }
            track = output
            input.startRecording()
            output.play()
            isRunning = true
            worker = Thread({
                try {
                    processLoop(input, output)
                } catch (_: Exception) {
                    isRunning = false
                }
            }, "GuitarFX-Audio").apply {
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
        // 128 frames at 48 kHz is about 2.7 ms per processing block.
        val pcm = ShortArray(128)
        val delayBuffer = FloatArray(sampleRate * 2)
        val chorusBuffer = FloatArray(sampleRate)
        val flangerBuffer = FloatArray((sampleRate * 0.03).toInt())
        var delayPos = 0
        var chorusPos = 0
        var flangerPos = 0
        var phase = 0.0
        var flangerPhase = 0.0
        var lowPass = 0.0
        var envelope = 0.0
        var bassLow = 0.0
        while (isRunning && !Thread.currentThread().isInterrupted) {
            val count = input.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
            // A negative read result indicates an audio I/O failure. Exit instead of
            // spinning at 100% CPU on a disconnected or invalidated input device.
            if (count < 0) {
                isRunning = false
                break
            }
            if (count == 0) continue
            var blockPeak = 0.0
            var blockSum = 0.0
            val effects = enabledEffects
            val driveGain = drive.coerceIn(1.0f, 8.0f).toDouble()
            for (i in 0 until count) {
                var x = pcm[i] / 32768.0
                val dryInput = abs(x)
                if (dryInput > blockPeak) blockPeak = dryInput
                blockSum += x * x
                envelope = envelope * 0.995 + abs(x) * 0.005
                if ("NOISE GATE" in effects && envelope < 0.012) x *= (envelope / 0.012).coerceIn(0.0, 1.0)
                if ("OVERDRIVE" in effects) x = tanh(x * driveGain) / tanh(driveGain)
                if ("AMP SIM" in effects) {
                    x = tanh(x * 2.6)
                    lowPass += 0.16 * (x - lowPass)
                    x = lowPass * 0.82 + x * 0.18
                }
                if ("CAB IR" in effects) {
                    // Cabinet-style high-frequency roll-off; not a true IR convolution.
                    lowPass += 0.10 * (x - lowPass)
                    x = lowPass
                }
                if ("FLANGER" in effects) {
                    // Short modulated delay with feedback for the sweeping Cure-style flange.
                    val lfo = (sin(flangerPhase) + 1.0) * 0.5
                    val delaySamples = (2.0 + lfo * 7.0).toInt().coerceIn(1, flangerBuffer.size - 1)
                    val readPos = (flangerPos - delaySamples + flangerBuffer.size) % flangerBuffer.size
                    val delayed = flangerBuffer[readPos].toDouble()
                    flangerBuffer[flangerPos] = (x + delayed * 0.55).toFloat()
                    x = x * 0.58 + delayed * 0.62
                    flangerPos = (flangerPos + 1) % flangerBuffer.size
                    flangerPhase += 2.0 * Math.PI * 0.22 / sampleRate
                    if (flangerPhase > 2.0 * Math.PI) flangerPhase -= 2.0 * Math.PI
                }
                if ("BASS PRESET" in effects) {
                    // Warm, rounded bass voicing inspired by post-punk bass tones.
                    bassLow += 0.075 * (x - bassLow)
                    x = bassLow * 1.18 + (x - bassLow) * 0.42
                    x = tanh(x * 1.18)
                }
                if ("CHORUS" in effects) {
                    val lfo = (sin(phase) + 1.0) * 0.5
                    val delaySamples = (18.0 + lfo * 18.0).toInt()
                    val readPos = (chorusPos - delaySamples + chorusBuffer.size) % chorusBuffer.size
                    val delayed = chorusBuffer[readPos]
                    chorusBuffer[chorusPos] = x.toFloat()
                    x = x * 0.72 + delayed * 0.28
                    chorusPos = (chorusPos + 1) % chorusBuffer.size
                    phase += 2.0 * Math.PI * 0.8 / sampleRate
                    if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                }
                if ("DELAY" in effects) {
                    val delaySamples = (sampleRate * 0.28).toInt()
                    val readPos = (delayPos - delaySamples + delayBuffer.size) % delayBuffer.size
                    val echo = delayBuffer[readPos]
                    delayBuffer[delayPos] = (x + echo * 0.38).toFloat()
                    x += echo * 0.35
                    delayPos = (delayPos + 1) % delayBuffer.size
                }
                x *= outputLevel.coerceIn(0.0f, 1.0f)
                pcm[i] = (x * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            }
            inputLevel = kotlin.math.sqrt(blockSum / count).toFloat().coerceIn(0f, 1f)
            peakLevel = blockPeak.toFloat().coerceIn(0f, 1f)
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
