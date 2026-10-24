package com.alang.guitarfx

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var engine: GuitarAudioEngine
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var driveValue: TextView
    private var drive = 2.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(15, 17, 20)
        window.navigationBarColor = Color.rgb(15, 17, 20)
        engine = GuitarAudioEngine(this)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 42)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(Color.rgb(15, 17, 20))
        }
        fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
            TextView(this).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(6))
            }

        root.addView(text("GUITAR FX", 28f, Color.rgb(182, 242, 58), true))
        root.addView(text("REAL-TIME GUITAR PROCESSOR", 12f, Color.LTGRAY, true))
        root.addView(text("INPUT • iRig 3.5 mm  →  OUTPUT • Earphone", 14f))
        status = text("READY — connect iRig, then start audio", 14f, Color.rgb(182, 242, 58))
        root.addView(status)
        root.addView(text("INPUT / OUTPUT LEVEL", 12f, Color.LTGRAY, true))
        root.addView(SeekBar(this).apply { max = 100; progress = 0; isEnabled = false })

        root.addView(text("EFFECTS", 18f, Color.WHITE, true))
        val effects = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("CLEAN", "OVERDRIVE", "DISTORTION", "FUZZ").forEachIndexed { index, label ->
            effects.addView(Button(this).apply {
                text = label
                textSize = 9f
                setOnClickListener {
                    drive = when (index) { 0 -> 1.0f; 1 -> 2.0f; 2 -> 4.0f; else -> 7.0f }
                    engine.drive = drive
                    driveValue.text = "Drive amount: " + "%.1fx".format(drive)
                    status.text = "Selected: " + label
                }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        root.addView(effects)
        driveValue = text("Drive amount: 2.0x", 14f)
        root.addView(driveValue)
        root.addView(SeekBar(this).apply {
            max = 69
            progress = 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    drive = 1.0f + progress / 10f
                    engine.drive = drive
                    driveValue.text = "Drive amount: " + "%.1fx".format(drive)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        startButton = Button(this).apply {
            text = "START AUDIO"
            setOnClickListener { toggleAudio() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
            topMargin = dp(20)
        })
        root.addView(text("Wired earphones are recommended. Bluetooth may have noticeable playing delay. " +
            "First-generation iRig routing depends on the phone's jack and audio policy.", 12f, Color.LTGRAY))
        root.addView(text("Prototype: live input + basic drive. Amp/cab IR and time effects are not implemented yet.", 11f, Color.GRAY))
        setContentView(root)
    }

    private fun toggleAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 42)
            return
        }
        if (engine.isRunning) {
            engine.stop()
            startButton.text = "START AUDIO"
            status.text = "Audio stopped"
        } else {
            status.text = engine.start()
            startButton.text = if (engine.isRunning) "STOP AUDIO" else "START AUDIO"
        }
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
