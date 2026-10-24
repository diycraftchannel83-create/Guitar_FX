package com.alang.guitarfx

import android.Manifest
import android.content.pm.PackageManager
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import android.app.AlertDialog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var engine: GuitarAudioEngine
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var recordButton: Button
    private var recordingUiActive = false
    private lateinit var outputValue: TextView
    private lateinit var outputSeek: SeekBar
    private lateinit var meterText: TextView
    private val meterBars = mutableListOf<View>()
    private val meterHandler = Handler(Looper.getMainLooper())
    private val meterUpdater = object : Runnable {
        override fun run() {
            if (!::engine.isInitialized || !::meterText.isInitialized) return
            val level = engine.inputLevel
            val peak = engine.peakLevel
            meterText.text = if (engine.isRunning) "RMS ${(level * 100).toInt()}%  •  PEAK ${(peak * 100).toInt()}%"
                else "NO LIVE SIGNAL"
            val lit = (peak * meterBars.size * 1.8f).toInt().coerceIn(0, meterBars.size)
            meterBars.forEachIndexed { index, bar ->
                val color = when {
                    index < lit && index >= 17 -> Color.rgb(255, 75, 75)
                    index < lit && index >= 13 -> Color.rgb(255, 185, 55)
                    index < lit -> lime
                    else -> Color.rgb(70, 75, 83)
                }
                bar.background = shape(color, 8f)
            }
            meterHandler.postDelayed(this, 80)
        }
    }
    private val active = mutableSetOf("OVERDRIVE")
    private var drive = 2.0f
    private var master = 75
    private val bg = Color.rgb(15, 17, 20)
    private val panel = Color.rgb(31, 34, 40)
    private val lime = Color.rgb(184, 244, 54)
    private val muted = Color.rgb(164, 170, 180)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        engine = GuitarAudioEngine(this)
        engine.enabledEffects = active.toSet()
        buildUi()
        meterHandler.post(meterUpdater)
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 42)
        }
    }

    private fun shape(color: Int, radius: Float = 22f): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat() }

    private fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun label(value: String, size: Float = 13f, color: Int = muted, bold: Boolean = false) =
        text(value, size, color, bold).apply { setPadding(0, dp(3), 0, dp(3)) }

    private fun sectionTitle(value: String, action: String? = null): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(value, 18f, Color.WHITE, true), LinearLayout.LayoutParams(0, dp(42), 1f))
        if (action != null) row.addView(text(action, 13f, lime, true))
        return row
    }

    private fun buildUi() {
        meterBars.clear()
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); clipToPadding = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(24))
            setBackgroundColor(bg)
        }
        scroll.addView(root)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(82, 86, 88), bg)).apply { cornerRadius = dp(24).toFloat() }
        }
        val mark = text("≋", 31f, bg, true).apply {
            gravity = Gravity.CENTER
            background = shape(lime, 18f)
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(62), dp(62)))
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titleColumn.addView(text("GUITAR FX PRO", 23f, Color.WHITE, true))
        titleColumn.addView(label("REAL-TIME GUITAR PROCESSOR", 11f))
        header.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(text("OFFLINE", 12f, Color.rgb(59, 202, 119), true).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7)); background = shape(Color.rgb(37, 61, 48), 30f)
        })
        root.addView(header)
        val divider = View(this).apply { setBackgroundColor(Color.rgb(57, 60, 66)) }
        root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(22); bottomMargin = dp(18) })

        val io = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val inputCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inputCol.addView(label("AUDIO INPUT"))
        inputCol.addView(text("iRig", 18f, Color.WHITE, true))
        io.addView(inputCol, LinearLayout.LayoutParams(0, dp(62), 1f))
        val outputCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.RIGHT }
        outputCol.addView(label("OUTPUT"))
        outputCol.addView(text("Earphone", 18f, lime, true))
        io.addView(outputCol, LinearLayout.LayoutParams(-1, dp(62), 1f))
        root.addView(io)
        status = label("READY  •  Connect iRig, then start audio", 12f, lime, true)
        root.addView(status, LinearLayout.LayoutParams(-1, dp(34)))

        val meterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = shape(panel, 22f)
        }
        val meterHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        meterHead.addView(text("INPUT LEVEL", 17f, Color.WHITE, true), LinearLayout.LayoutParams(0, dp(34), 1f))
        meterHead.addView(text("SIGNAL METER", 13f, lime))
        meterCard.addView(meterHead)
        val bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (i in 0 until 20) {
            val bar = View(this).apply { background = shape(Color.rgb(70, 75, 83), 8f) }
            meterBars.add(bar)
            bars.addView(bar, LinearLayout.LayoutParams(0, dp(if (i % 4 == 0) 29 else 19), 1f).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        meterCard.addView(bars, LinearLayout.LayoutParams(-1, dp(40)))
        meterText = label("NO LIVE SIGNAL  •  Start audio to measure input", 11f, lime, true)
        meterCard.addView(meterText)
        root.addView(meterCard, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14); bottomMargin = dp(18)
        })

        root.addView(sectionTitle("MY PEDALBOARD", "EDIT CHAIN"))
        val grid = GridLayout(this).apply { columnCount = 3; useDefaultMargins = false }
        val pedals = listOf(
            Triple("NOISE GATE", "NOISE GATE", Color.rgb(61, 91, 99)),
            Triple("OVERDRIVE", "OVERDRIVE", lime),
            Triple("MUSE ROCK DIST", "MUSE ROCK DISTORTION", Color.rgb(188, 66, 70)),
            Triple("AMP SIM", "AMP SIM", Color.rgb(74, 75, 103)),
            Triple("CAB IR", "CAB IR", Color.rgb(66, 72, 80)),
            Triple("CHORUS", "CHORUS", Color.rgb(73, 101, 123)),
            Triple("DELAY", "DELAY", Color.rgb(117, 89, 58)),
            Triple("FLANGER", "FLANGER", Color.rgb(102, 65, 125)),
            Triple("SIMON GALLOP BASS", "BASS PRESET", Color.rgb(54, 91, 83))
        )
        pedals.forEach { (title, key, color) ->
            val on = active.contains(key)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(8), dp(10))
                background = shape(if (on) lime else color, 20f)
                isClickable = true
                isFocusable = true
            }
            val knob = TextView(this).apply {
                text = "●"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(if (on) lime else Color.rgb(221, 226, 233))
                background = shape(Color.rgb(22, 24, 29), 40f)
            }
            card.addView(knob, LinearLayout.LayoutParams(dp(42), dp(42)))
            card.addView(text(title, 12f, if (on) bg else Color.WHITE, true).apply { setPadding(0, dp(9), 0, dp(3)) })
            card.addView(text(if (on) "ON" else "ON / OFF", 11f, if (on) bg else Color.rgb(225, 228, 233)))
            card.setOnClickListener {
                if (active.contains(key)) active.remove(key) else active.add(key)
                engine.enabledEffects = active.toSet()
                if (key == "OVERDRIVE") {
                    drive = if (active.contains(key)) 2.0f else 1.0f
                    engine.drive = drive
                }
                buildUi()
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0; height = dp(132); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(4), dp(3), dp(4))
            }
            grid.addView(card, params)
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) })

        val masterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = shape(panel, 22f)
        }
        val masterHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        masterHead.addView(text("MASTER OUTPUT", 17f, Color.WHITE, true), LinearLayout.LayoutParams(0, dp(36), 1f))
        outputValue = text("$master%", 17f, lime, true)
        masterHead.addView(outputValue)
        masterCard.addView(masterHead)
        outputSeek = SeekBar(this).apply {
            max = 100; progress = master
            progressTintList = android.content.res.ColorStateList.valueOf(lime)
            thumbTintList = android.content.res.ColorStateList.valueOf(lime)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    master = progress; outputValue.text = "$master%"
                    engine.outputLevel = master / 100f
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        masterCard.addView(outputSeek)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val monitor = Button(this).apply {
            text = "♬  MONITOR"; isAllCaps = false; textSize = 13f
            setTextColor(lime); background = shape(panel, 16f)
            setOnClickListener { status.text = "Use wired earphones to monitor with lower latency." }
        }
        buttons.addView(monitor, LinearLayout.LayoutParams(0, dp(62), 1f).apply { rightMargin = dp(6) })
        startButton = Button(this).apply {
            text = "⏻  START AUDIO"; isAllCaps = false; textSize = 13f
            setTextColor(bg); background = shape(lime, 16f)
            setOnClickListener { toggleAudio() }
        }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, dp(62), 1f).apply { leftMargin = dp(6) })
        masterCard.addView(buttons)
        recordButton = Button(this).apply {
            text = if (recordingUiActive) "■  FINISH RECORDING" else "●  RECORD MP3"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = shape(Color.rgb(176, 48, 58), 16f)
            setOnClickListener { toggleRecording() }
        }
        masterCard.addView(recordButton, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(10) })
        root.addView(masterCard)
        root.addView(label("Wired earphones recommended. Bluetooth can introduce noticeable latency.", 11f).apply {
            setPadding(2, dp(12), 2, dp(2))
        })
        root.addView(label("Live DSP: overdrive, high-gain modern rock distortion, amp tone, cabinet-style filter, chorus, flanger, delay and post-punk bass voicing. CAB IR is a tone filter, not a measured impulse response.", 10f, Color.GRAY))
        setContentView(scroll)
    }

    private fun toggleRecording() {
        if (!engine.isRunning) {
            status.text = "Start audio before recording"
            Toast.makeText(this, "Start audio first, then press Record MP3.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!recordingUiActive) {
            status.text = engine.startRecording()
            recordingUiActive = engine.recordingError == null && status.text.toString().contains("Recording processed")
            recordButton.text = if (recordingUiActive) "■  FINISH RECORDING" else "●  RECORD MP3"
        } else {
            val finished = engine.stopRecording()
            recordingUiActive = false
            recordButton.text = "●  RECORD MP3"
            if (finished == null) {
                status.text = "MP3 recording failed: ${engine.recordingError ?: "no audio data"}"
                Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            } else {
                status.text = "Recording finished — choose MP3 filename"
                askRecordingName(finished)
            }
        }
    }

    private fun askRecordingName(tempFile: File) {
        val input = EditText(this).apply {
            hint = "Enter recording name"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText("GuitarFX_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Save MP3 recording")
            .setMessage("Folder: Music/audio/guitar_fx")
            .setView(input)
            .setNegativeButton("Cancel") { _, _ -> tempFile.delete(); status.text = "Recording discarded" }
            .setPositiveButton("Save") { _, _ ->
                val rawName = input.text.toString().trim()
                val safeName = rawName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "GuitarFX_Recording" }
                saveRecording(tempFile, safeName)
            }
            .setOnCancelListener { tempFile.delete(); status.text = "Recording discarded" }
            .show()
    }

    private fun saveRecording(tempFile: File, name: String) {
        val filename = if (name.lowercase(java.util.Locale.ROOT).endsWith(".mp3")) name else "$name.mp3"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/audio/guitar_fx")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create MP3 media entry")
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(tempFile).use { it.copyTo(output) }
                    } ?: throw IllegalStateException("Could not open output file")
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
            } else {
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "audio/guitar_fx")
                if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Could not create output folder")
                val destination = File(folder, filename)
                FileInputStream(tempFile).use { input -> FileOutputStream(destination).use { output -> input.copyTo(output) } }
            }
            tempFile.delete()
            status.text = "Saved: Music/audio/guitar_fx/$filename"
            Toast.makeText(this, "Saved MP3: Music/audio/guitar_fx/$filename", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            status.text = "Save failed: ${e.message ?: e.javaClass.simpleName}"
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            return
        }
        if (engine.isRunning) {
            if (recordingUiActive) {
                val finished = engine.stopRecording()
                recordingUiActive = false
                if (finished != null) askRecordingName(finished)
                else status.text = "Recording ended: ${engine.recordingError ?: "no audio data"}"
                if (::recordButton.isInitialized) recordButton.text = "●  RECORD MP3"
            }
            engine.stop()
            startButton.text = "⏻  START AUDIO"
            if (status.text.toString().startsWith("READY") || status.text.toString().startsWith("Recording ended")) status.text = "Audio stopped"
        } else {
            status.text = engine.start()
            startButton.text = if (engine.isRunning) "■  STOP AUDIO" else "⏻  START AUDIO"
        }
    }

    override fun onDestroy() {
        meterHandler.removeCallbacks(meterUpdater)
        engine.stop()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
