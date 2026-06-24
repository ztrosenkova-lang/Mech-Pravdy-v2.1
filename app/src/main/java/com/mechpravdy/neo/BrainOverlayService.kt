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

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Создаем простое полупрозрачное плавающее окно поверх всех экранов
        floatingView = TextView(this).apply {
            text = "НЕО: МОЗГ"
            setBackgroundColor(Color.parseColor("#CC000000")) // Полупрозрачный черный
            setTextColor(Color.GREEN)
            setPadding(16, 16, 16, 16)
            textSize = 12f
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
            gravity = Gravity.TOP or Gravity.END
            x = 10
            y = 100
        }

        windowManager.addView(floatingView, params)

        // Инициализируем файлы обмена данными
        val queryFile = File(filesDir, "brain_query.txt")
        val responseFile = File(filesDir, "brain_response.txt")

        // Следим за командами от главного чата Меча
        queryObserver = object : FileObserver(queryFile.path, CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                val queryText = try { queryFile.readText().trim() } catch (_: Exception) { "" }
                if (queryText.isNotBlank()) {
                    try { queryFile.writeText("") } catch (_: Exception) {} // Чистим запрос

                    Thread {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        try {
                            // Проверяем JNI и генерируем ответ из GGUF
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
    }

    override fun onDestroy() {
        super.onDestroy()
        queryObserver?.stopWatching()
        floatingView?.let { windowManager.removeView(it) }
        
        // Выгружаем модель из памяти этого процесса при закрытии сервиса
        try {
            LlamaJNI.unloadModel()
        } catch (_: Exception) {}
    }

    companion object {
        init {
            try {
                System.loadLibrary("llama") // Загрузка C++ ядра строго внутри процесса Мозга
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("MECH_BRAIN", "Ошибка линковки JNI: ${e.message}")
            }
        }
    }
}
