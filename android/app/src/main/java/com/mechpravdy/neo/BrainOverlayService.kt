package com.mechpravdy.neo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.io.File

class BrainOverlayService : Service() {

    companion object {
        init {
            System.loadLibrary("rnllama")
        }
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: TextView? = null
    private var queryObserver: FileObserver? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelPath = intent?.getStringExtra("MODEL_PATH")
        if (modelPath.isNullOrEmpty() || !java.io.File(modelPath).exists() || java.io.File(modelPath).length() < 10 * 1024 * 1024) {
            android.util.Log.e("MECH_BRAIN", "Файл модели отсутствует или поврежден!")
            handler.post { floatingView?.text = "НЕО: БИТЫЙ ФАЙЛ" }
        } else {
            // ===== ЗАМЕНЕННЫЙ БЛОК Thread =====
            Thread {
                try {
                    // Передаем два параметра: путь и размер контекста 2048
                    val ok = LlamaJNI.loadModel(modelPath, 2048)
                    handler.post {
                        floatingView?.text = if (ok) "НЕО: ГОТОВ" else "НЕО: ОШИБКА"
                    }
                } catch (e: Throwable) { // Ловим критические краши JNI и C++ слоя
                    handler.post { floatingView?.text = "НЕО: КРАШ ДВИЖКА" }
                }
            }.start()
            // ===== КОНЕЦ ЗАМЕНЕННОГО БЛОКА =====
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        floatingView = TextView(this).apply {
            text = "НЕО: МОЗГ"
            setTextColor(Color.parseColor("#1A8A2E"))
            setPadding(24, 16, 24, 16)
            textSize = 12f
            
            val shape = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#C0FFFFFF"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#1A8A2E"))
            }
            background = shape
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0 
            y = 120
        }

        windowManager.addView(floatingView, params)

        val queryFile = File(filesDir, "brain_query.txt")
        val responseFile = File(filesDir, "brain_response.txt")

        queryObserver = object : FileObserver(queryFile.path, CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                val queryText = try { queryFile.readText().trim() } catch (_: Exception) { "" }
                if (queryText.isNotBlank()) {
                    try { queryFile.writeText("") } catch (_: Exception) {}

                    Thread {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        try {
                            // ===== ИСПРАВЛЕНИЕ ГЕНЕРАЦИИ =====
                            // Убрали число 512, метод принимает только текст
                            val aiResponse = if (LlamaJNI.isModelLoaded()) {
                                LlamaJNI.generate(queryText) // ТОЛЬКО ОДИН ПАРАМЕТР!
                            } else {
                                "[МОЗГ] Ошибка: GGUF модель не загружена в память процесса."
                            }
                            // ===== КОНЕЦ ИСПРАВЛЕНИЯ =====
                            responseFile.writeText(aiResponse)
                        } catch (e: Exception) {
                            try { responseFile.writeText("[ОШИБКА GGUF] ${e.message}") } catch (_: Exception) {}
                        }
                    }.start()
                }
            }
        }
        queryObserver?.startWatching()
    }

    override fun onDestroy() {
        super.onDestroy()
        queryObserver?.stopWatching()
        floatingView?.let { windowManager.removeView(it) }
        
        try {
            LlamaJNI.unloadModel()
        } catch (_: Exception) {}
    }
}
