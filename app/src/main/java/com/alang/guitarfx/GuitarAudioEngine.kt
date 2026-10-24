package com.alang.guitarfx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
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
    @Volatile private var recordingActive = false
    @Volatile var recordingError: String? = null
        private set
    private var recordingQueue: ArrayBlockingQueue<ShortArray>? = null
    private var recordingThread: Thread? = null
    private var recordingFile: File? = null

    @Synchronized
    fun startRecording(): String {
        if (!isRunning) return "Start audio before recording"
        if (recordingActive) return "Recording is already active"
        return try {
            val file = File(context.cacheDir, "guitarfx_recording_${System.currentTimeMillis()}.mp3")
            val queue = ArrayBlockingQueue<ShortArray>(64)
            recordingFile = file
            recordingQueue = queue
            recordingError = null
            recordingActive = true
            recordingThread = Thread({
                var encoder: AndroidLame? = null
                try {
                    val lame = LameBuilder()
                        .setInSampleRate(sampleRate)
                        .setOutSampleRate(sampleRate)
                        .setOutChannels(1)
                        .setOutBitrate(192)
                        .setMode(LameBuilder.Mode.MONO)
                        .setQuality(5)
                        .build()
                    encoder = lame
                    FileOutputStream(file).use { stream ->
                        val mp3Buffer = ByteArray(8192)
                        while (recordingActive || queue.isNotEmpty()) {
                            val block = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                            val bytes = lame.encodeBufferInterLeaved(block, block.size, mp3Buffer)
                            if (bytes > 0) stream.write(mp3Buffer, 0, bytes)
                        }
                        val flushed = lame.flush(mp3Buffer)
                        if (flushed > 0) stream.write(mp3Buffer, 0, flushed)
                        stream.flush()
                    }
                } catch (e: Exception) {
                    recordingError = e.message ?: e.javaClass.simpleName
                } finally {
                    try { encoder?.close() } catch (_: Exception) { }
                }
            }, "GuitarFX-MP3-Encoder").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }
            "Recording processed guitar audio to MP3"
        } catch (e: Exception) {
            recordingActive = false
            recordingQueue = null
            recordingThread = null
            recordingFile = null
            "Could not start recording: ${e.javaClass.simpleName}"
        }
    }

    @Synchronized
    fun stopRecording(): File? {
        if (!recordingActive && recordingThread == null) return null
        recordingActive = false
        try { recordingThread?.join(5000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        val finished = recordingFile
        recordingQueue = null
        recordingThread = null
        recordingFile = null
        return if (recordingError == null && finished?.exists() == true && finished.length() > 0) finished else null
    }

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
                if ("MUSE ROCK DISTORTION" in effects) {
                    // High-gain, mid-forward modern alternative-rock voicing.
                    // Asymmetric clipping adds bite while a small low-end trim keeps chords defined.
                    val tightened = x * 0.88
                    val hot = tightened * 5.2
                    val clipped = tanh(hot) * 0.78 + tanh(hot * 0.55 + 0.12) * 0.22
                    x = clipped * 1.05
                    lowPass += 0.22 * (x - lowPass)
                    x = lowPass * 0.72 + x * 0.28
                }
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
            // Record the processed (wet) signal on a separate thread so MP3 encoding
            // does not block the real-time audio path.
            if (recordingActive) recordingQueue?.offer(pcm.copyOf(count))
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
        stopRecording()
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
