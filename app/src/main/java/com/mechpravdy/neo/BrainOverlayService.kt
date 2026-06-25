package com.mechpravdy.neo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File

class BrainOverlayService : Activity() {
    private var floatingLayout: LinearLayout? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var queryObserver: FileObserver? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Настраиваем окно Activity как плавающий оверлей
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            @Suppress("DEPRECATION")
            window.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }

        val params = window.attributes
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = 120
        params.width = ViewGroup.LayoutParams.WRAP_CONTENT
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
        window.attributes = params

        // Создаём контейнер — вертикальный LinearLayout
        floatingLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)

            val shape = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#C0FFFFFF"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#1A8A2E"))
            }
            background = shape
        }

        // Текст статуса
        statusText = TextView(this).apply {
            text = "НЕО: МОЗГ"
            setTextColor(Color.parseColor("#1A8A2E"))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        floatingLayout?.addView(statusText)

        // Полоска загрузки
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            setPadding(0, 8, 0, 0)
        }
        floatingLayout?.addView(progressBar)

        setContentView(floatingLayout)

        // Загружаем модель, если путь передан
        val path = intent?.getStringExtra("MODEL_PATH")
        if (path != null && File(path).exists()) {
            updateStatus("ЗАГРУЖАЮ МОДЕЛЬ...")
            showProgress(true)
            Thread {
                try {
                    val ok = LlamaJNI.loadModel(path, 2048)
                    mainHandler.post {
                        if (ok) updateStatus("НЕО: ГОТОВ") else updateStatus("ОШИБКА ЗАГРУЗКИ")
                        showProgress(false)
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        updateStatus("ОШИБКА: ${e.message}")
                        showProgress(false)
                    }
                }
            }.start()
        }

        // Наблюдатель за файлом запросов
        val queryFile = File(filesDir, "brain_query.txt")
        val responseFile = File(filesDir, "brain_response.txt")

        queryObserver = object : FileObserver(queryFile.path, CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                val queryText = try { queryFile.readText().trim() } catch (_: Exception) { "" }
                if (queryText.isNotBlank()) {
                    try { queryFile.writeText("") } catch (_: Exception) {}

                    mainHandler.post {
                        updateStatus("ДУМАЮ...")
                        showProgress(true)
                    }

                    Thread {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        try {
                            val aiResponse = if (LlamaJNI.isModelLoaded()) {
                                LlamaJNI.generate(queryText, 512)
                            } else {
                                "[МОЗГ] Ошибка: GGUF модель не загружена в память процесса."
                            }
                            responseFile.writeText(aiResponse)

                            mainHandler.post {
                                updateStatus("НЕО: ГОТОВ")
                                showProgress(false)
                            }
                        } catch (e: Exception) {
                            try { responseFile.writeText("[ОШИБКА GGUF] ${e.message}") } catch (_: Exception) {}
                            mainHandler.post {
                                updateStatus("ОШИБКА: ${e.message}")
                                showProgress(false)
                            }
                        }
                    }.start()
                }
            }
        }
        queryObserver?.startWatching()

        // Фоновая загрузка библиотеки
        Thread {
            try {
                System.loadLibrary("llama")
                android.util.Log.d("MECH_BRAIN", "libllama.so загружена в Activity оверлея.")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("MECH_BRAIN", "Ошибка линковки JNI: ${e.message}")
            }
        }.start()
    }

    private fun updateStatus(text: String) {
        statusText?.text = text
    }

    private fun showProgress(show: Boolean) {
        progressBar?.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        queryObserver?.stopWatching()
        try {
            LlamaJNI.unloadModel()
        } catch (_: Exception) {}
    }
}
