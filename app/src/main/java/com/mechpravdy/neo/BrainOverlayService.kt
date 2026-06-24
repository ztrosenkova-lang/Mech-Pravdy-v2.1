package com.mechpravdy.neo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.os.Process
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.io.File

class BrainOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var floatingView: TextView? = null
    private var queryObserver: FileObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra("MODEL_PATH")
        if (path != null && java.io.File(path).exists()) {
            Thread {
                try {
                    val ok = LlamaJNI.loadModel(path, 2048)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        floatingView?.text = if (ok) "НЕО: ГОТОВ" else "НЕО: ОШИБКА"
                    }
                } catch (_: Exception) {}
            }.start()
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Создаем стильное бело-прозрачное окно с закругленными краями
        floatingView = TextView(this).apply {
            text = "НЕО: МОЗГ"
            setTextColor(Color.parseColor("#1A8A2E")) // Фирменный зеленый цвет Меча
            setPadding(24, 16, 24, 16)
            textSize = 12f
            
            // Нативно закругляем края и делаем бело-прозрачный фон (75% непрозрачности)
            val shape = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#C0FFFFFF")) // Белый цвет + альфа-канал прозрачности
                cornerRadius = 24f // Радиус закругления краев окна
                setStroke(2, Color.parseColor("#1A8A2E")) // Тонкая зеленая рамка по контуру
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
            // ИСПРАВЛЕНО: Убираем привязку к краю экрана (Gravity.END)
            // Центрируем окно по горизонтали, чтобы оно встало ровно посередине рамки приложения
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0 
            y = 120 // Высота от верха экрана, чтобы окно встало ровно на уровне Мурзехи
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
                            val aiResponse = if (LlamaJNI.isModelLoaded()) {
                                LlamaJNI.generate(queryText, 512)
                            } else {
                                "[МОЗГ] Ошибка: GGUF модель не загружена в память процесса."
                            }
                            responseFile.writeText(aiResponse)
                        } catch (e: Exception) {
                            try { responseFile.writeText("[ОШИБКА GGUF] ${e.message}") } catch (_: Exception) {}
                        }
                    }.start()
                }
            }
        }
        queryObserver?.startWatching()

        // ДИНАМИЧЕСКАЯ ЗАГРУЗКА: C++ слой начинает грузиться ТОЛЬКО СЕЙЧАС,
        // когда сервис уже успешно запущен кнопкой, полностью изолированно от Меча!
        Thread {
            try {
                System.loadLibrary("llama")
                android.util.Log.d("MECH_BRAIN", "libllama.so успешно загружена в фоне процесса.")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("MECH_BRAIN", "Ошибка отложенной линковки JNI: ${e.message}")
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        queryObserver?.stopWatching()
        floatingView?.let { windowManager.removeView(it) }
        
        // ВЫГРУЗКА ИЗ ПАМЯТИ: Полностью очищаем ОЗУ при выключении кнопки
        try {
            LlamaJNI.unloadModel()
        } catch (_: Exception) {}
    }
}
