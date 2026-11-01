package com.tesaauto.mirror.display

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SecondaryDisplayPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private lateinit var clockTextView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val timeUpdater = object : Runnable {
        override fun run() {
            clockTextView.text = dateFormat.format(Date())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F141C"))
            setPadding(48, 48, 48, 48)
        }

        val logoText = TextView(context).apply {
            text = "TesAAuto Mirror"
            textSize = 38f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        clockTextView = TextView(context).apply {
            text = dateFormat.format(Date())
            textSize = 56f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#3498DB"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        val statusText = TextView(context).apply {
            text = "Pantalla Secundaria Activa\nListo para proyectar aplicaciones (Waze, Maps, Multimedia)"
            textSize = 20f
            setTextColor(Color.parseColor("#BDC3C7"))
            gravity = Gravity.CENTER
            setLineSpacing(8f, 1f)
        }

        val hintText = TextView(context).apply {
            text = "Selecciona una aplicación desde la pantalla del móvil para iniciar la proyección"
            textSize = 14f
            setTextColor(Color.parseColor("#7F8C8D"))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }

        layout.addView(logoText)
        layout.addView(clockTextView)
        layout.addView(statusText)
        layout.addView(hintText)

        setContentView(layout)
    }

    override fun onStart() {
        super.onStart()
        handler.post(timeUpdater)
    }

    override fun onStop() {
        handler.removeCallbacks(timeUpdater)
        super.onStop()
    }
}
