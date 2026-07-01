package com.mechpravdy.neo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.io.File

class BrainOverlayService : Service() {

    // ===== УДАЛЕН companion object с System.loadLibrary("rnllama") =====

    private lateinit var windowManager: WindowManager
    private var floatingView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelPath = intent?.getStringExtra("MODEL_PATH")
        val file = modelPath?.let { java.io.File(it) }

        // 1. ПРОВЕРКА НАЛИЧИЯ И ДОСТУПА К ФАЙЛУ
        if (modelPath.isNullOrEmpty() || file == null || !file.exists() || file.length() < 10 * 1024 * 1024) {
            android.util.Log.e("MECH_BRAIN", "Файл модели отсутствует или поврежден: $modelPath")
            handler.post { floatingView?.text = "НЕО: НЕТ ФАЙЛА" }
            return START_STICKY
        }

        if (!file.canRead()) {
            android.util.Log.e("MECH_BRAIN", "Ошибка доступа! Файл не может быть прочитан: $modelPath")
            handler.post { floatingView?.text = "НЕО: ОШИБКА ДОСТУПА" }
            return START_STICKY
        }

        // 2. ВЫВОД СТАТУСА НАЧАЛА ЗАГРУЗКИ
        handler.post { floatingView?.text = "НЕО: ЗАГРУЗКА..." }

        // 3. ЕДИНСТВЕННЫЙ БОЕВОЙ ЦИКЛ В ФОНОВОМ ПОТОКЕ
        Thread {
            try {
                // Загружаем модель
                val ok = LlamaJNI.loadModel(this, modelPath, 4096)
                
                handler.post {
                    floatingView?.text = if (ok) "НЕО: ГОТОВ" else "НЕО: ОШИБКА ДВИЖКА"
                }

                if (ok) {
                    val queryFile = File(filesDir, "brain_query.txt")
                    val responseFile = File(filesDir, "brain_response.txt")

                    while (LlamaJNI.isModelLoaded()) {
                        // Жесткая разгрузка процессора MediaTek Helio G88
                        Thread.sleep(300) 

                        if (queryFile.exists()) {
                            val rawPrompt = queryFile.readText().trim()
                            
                            if (rawPrompt.isNotBlank()) {
                                if (rawPrompt == "COMMAND_UNLOAD_MODEL") {
                                    queryFile.writeText("")
                                    break
                                }

                                handler.post { floatingView?.text = "НЕО: ДУМАЕТ..." }
                                queryFile.writeText("") // Очищаем мост ввода

                                // Безопасный вызов официального инференса
                                val aiResponse = LlamaJNI.generate(rawPrompt)

                                responseFile.writeText(aiResponse) // Отдаем ответ Бате
                                handler.post { floatingView?.text = "НЕО: ГОТОВ" }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("MECH_BRAIN", "Критический краш потока инференса: ${e.message}")
                handler.post { floatingView?.text = "НЕО: КРАШ ДВИЖКА" }
            }
        }.start()

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
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let { windowManager.removeView(it) }
        
        try {
            LlamaJNI.unloadModel()
        } catch (_: Exception) {}
    }
}
