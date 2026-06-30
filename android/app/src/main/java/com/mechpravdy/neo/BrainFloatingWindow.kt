package com.mechpravdy.neo

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class BrainFloatingWindow : AppCompatActivity() {

    // ===== СИСТЕМНЫЕ ПЕРЕМЕННЫЕ ОВЕРЛЕЯ =====
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var statusTextView: TextView
    private lateinit var progressBar: ProgressBar

    private var queryObserver: FileObserver? = null
    private var modelLoaded = false
    private val brainQueryFile by lazy { File(filesDir, "brain_query.txt") }
    private val brainResponseFile by lazy { File(filesDir, "brain_response.txt") }

    // ===== ИСПРАВЛЕННЫЙ ПОРЯДОК ВЫЗОВОВ В onCreate =====
    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Сначала базовый метод (инициализирует тему и контекст)
        super.onCreate(savedInstanceState)

        // 2. Теперь безопасно создаем оверлей через WindowManager
        setupFloatingWindow()
        
        // 3. Проверка разрешения
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
            return
        }
        
        // 4. Инициализация файлов
        initCommunicationFiles()
        
        // 5. Загрузка модели
        val modelPath = intent.getStringExtra("MODEL_PATH")
        if (!modelPath.isNullOrEmpty()) {
            loadModel(modelPath)
        }
    }

    override fun onResume() {
        super.onResume()
        startQueryObserver()
    }

    override fun onPause() {
        super.onPause()
        queryObserver?.stopWatching()
    }

    override fun onDestroy() {
        super.onDestroy()
        queryObserver?.stopWatching()
        queryObserver = null
        
        // Удаляем оверлей
        try {
            if (::windowManager.isInitialized && ::overlayView.isInitialized) {
                windowManager.removeView(overlayView)
                Log.d("MECH_BRAIN", "Оверлей удален")
            }
        } catch (e: Exception) {
            Log.e("MECH_BRAIN", "Ошибка удаления оверлея: ${e.message}")
        }
        
        // Выгружаем модель
        if (modelLoaded) {
            try {
                LlamaJNI.unloadModel()
                modelLoaded = false
                Log.d("MECH_BRAIN", "Модель выгружена")
            } catch (e: Exception) {
                Log.e("MECH_BRAIN", "Ошибка выгрузки модели: ${e.message}")
            }
        }
    }

    private fun setupFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Создаем корневой LinearLayout
        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
            
            // Белый фон с зеленой рамкой и скруглением
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#D9FFFFFF"))
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor("#1A8A2E"))
                cornerRadius = 16 * resources.displayMetrics.density
            }
        }
        
        // Создаем TextView для статуса
        statusTextView = TextView(this).apply {
            text = "НЕО: ЗАГРУЗКА..."
            textSize = 14f
            setTextColor(Color.parseColor("#1A8A2E"))
            gravity = Gravity.CENTER
            setPadding(10, 10, 10, 10)
        }
        
        // Создаем ProgressBar
        progressBar = ProgressBar(this).apply {
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 0)
            }
        }
        
        // Собираем вьюхи в контейнер
        overlayView.addView(statusTextView)
        overlayView.addView(progressBar)
        
        // Настройка параметров окна
        val params = WindowManager.LayoutParams().apply {
            // Тип окна
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            // Флаги
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            
            // Формат пикселей
            format = android.graphics.PixelFormat.TRANSLUCENT
            
            // Размеры и позиция
            width = (250 * resources.displayMetrics.density).toInt()
            height = (350 * resources.displayMetrics.density).toInt()
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (150 * resources.displayMetrics.density).toInt()
        }
        
        // Добавляем оверлей в WindowManager
        try {
            windowManager.addView(overlayView, params)
            Log.d("MECH_BRAIN", "Оверлей успешно создан через WindowManager")
        } catch (e: Exception) {
            Log.e("MECH_BRAIN", "Ошибка создания оверлея: ${e.message}")
            // В случае ошибки пытаемся использовать альтернативные параметры
            try {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                windowManager.addView(overlayView, params)
                Log.d("MECH_BRAIN", "Оверлей создан с альтернативными параметрами")
            } catch (e2: Exception) {
                Log.e("MECH_BRAIN", "Критическая ошибка создания оверлея: ${e2.message}")
            }
        }
    }

    private fun initCommunicationFiles() {
        try {
            if (!brainQueryFile.exists()) {
                brainQueryFile.createNewFile()
                Log.d("MECH_BRAIN", "Создан файл запросов: ${brainQueryFile.absolutePath}")
            }
            if (!brainResponseFile.exists()) {
                brainResponseFile.createNewFile()
                Log.d("MECH_BRAIN", "Создан файл ответов: ${brainResponseFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("MECH_BRAIN", "Ошибка инициализации файлов: ${e.message}")
        }
    }

    private fun loadModel(modelPath: String) {
        Thread {
            try {
                Log.d("MECH_BRAIN", "Загрузка модели из: $modelPath")
                
                runOnUiThread {
                    statusTextView.text = "НЕО: ЗАГРУЗКА МОДЕЛИ..."
                    progressBar.visibility = View.VISIBLE
                }
                
                val result = LlamaJNI.loadModel(modelPath)
                if (result) {
                    modelLoaded = true
                    Log.d("MECH_BRAIN", "Модель успешно загружена")
                    
                    runOnUiThread {
                        statusTextView.text = "НЕО: ГОТОВ"
                        progressBar.visibility = View.GONE
                        try {
                            brainResponseFile.writeText("Модель загружена. Я готов к диалогу.")
                        } catch (e: Exception) {
                            Log.e("MECH_BRAIN", "Ошибка записи статуса: ${e.message}")
                        }
                    }
                } else {
                    Log.e("MECH_BRAIN", "Ошибка загрузки модели")
                    runOnUiThread {
                        statusTextView.text = "НЕО: ОШИБКА ЗАГРУЗКИ"
                        progressBar.visibility = View.GONE
                        try {
                            brainResponseFile.writeText("Ошибка загрузки модели. Проверьте файл.")
                        } catch (e: Exception) {
                            Log.e("MECH_BRAIN", "Ошибка записи статуса: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MECH_BRAIN", "Исключение при загрузке модели: ${e.message}")
                runOnUiThread {
                    statusTextView.text = "НЕО: КРИТИЧЕСКАЯ ОШИБКА"
                    progressBar.visibility = View.GONE
                    try {
                        brainResponseFile.writeText("Критическая ошибка: ${e.message}")
                    } catch (ex: Exception) {
                        Log.e("MECH_BRAIN", "Ошибка записи статуса: ${ex.message}")
                    }
                }
            }
        }.start()
    }

    private fun startQueryObserver() {
        if (queryObserver != null) {
            queryObserver?.stopWatching()
            queryObserver = null
        }

        queryObserver = object : FileObserver(
            brainQueryFile.absolutePath,
            FileObserver.CLOSE_WRITE or FileObserver.MODIFY
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (event and (FileObserver.CLOSE_WRITE or FileObserver.MODIFY) == 0) {
                    return
                }

                try {
                    val queryText = brainQueryFile.readText().trim()
                    if (queryText.isEmpty()) return

                    Log.d("MECH_BRAIN", "Получен запрос: $queryText")

                    if (queryText == "COMMAND_UNLOAD_MODEL") {
                        handleUnloadModel()
                        return
                    }

                    handleQuery(queryText)
                } catch (e: Exception) {
                    Log.e("MECH_BRAIN", "Ошибка обработки запроса: ${e.message}")
                }
            }
        }

        queryObserver?.startWatching()
        Log.d("MECH_BRAIN", "FileObserver запущен для: ${brainQueryFile.absolutePath}")
    }

    private fun handleUnloadModel() {
        Thread {
            try {
                runOnUiThread {
                    statusTextView.text = "НЕО: ВЫГРУЗКА..."
                    progressBar.visibility = View.VISIBLE
                }
                
                if (modelLoaded) {
                    LlamaJNI.unloadModel()
                    modelLoaded = false
                    Log.d("MECH_BRAIN", "Модель выгружена по команде")
                    
                    runOnUiThread {
                        statusTextView.text = "НЕО: ВЫГРУЖЕНА"
                        progressBar.visibility = View.GONE
                        try {
                            brainResponseFile.writeText("Модель выгружена")
                        } catch (e: Exception) {
                            Log.e("MECH_BRAIN", "Ошибка записи статуса: ${e.message}")
                        }
                    }
                } else {
                    Log.d("MECH_BRAIN", "Модель уже выгружена")
                    runOnUiThread {
                        statusTextView.text = "НЕО: ГОТОВ"
                        progressBar.visibility = View.GONE
                    }
                }
                
                brainQueryFile.writeText("")
            } catch (e: Exception) {
                Log.e("MECH_BRAIN", "Ошибка выгрузки модели: ${e.message}")
                runOnUiThread {
                    statusTextView.text = "НЕО: ОШИБКА ВЫГРУЗКИ"
                    progressBar.visibility = View.GONE
                    try {
                        brainResponseFile.writeText("Ошибка выгрузки: ${e.message}")
                    } catch (ex: Exception) {
                        Log.e("MECH_BRAIN", "Ошибка записи статуса: ${ex.message}")
                    }
                }
            }
        }.start()
    }

    private fun handleQuery(queryText: String) {
        Thread {
            try {
                if (!modelLoaded) {
                    runOnUiThread {
                        statusTextView.text = "НЕО: МОДЕЛЬ НЕ ЗАГРУЖЕНА"
                        try {
                            brainResponseFile.writeText("Модель не загружена. Подождите...")
                        } catch (e: Exception) {
                            Log.e("MECH_BRAIN", "Ошибка записи ответа: ${e.message}")
                        }
                    }
                    return@Thread
                }

                Log.d("MECH_BRAIN", "Генерация ответа для запроса: $queryText")
                
                runOnUiThread {
                    statusTextView.text = "НЕО: ДУМАЕТ..."
                    progressBar.visibility = View.VISIBLE
                }
                
                val response = LlamaJNI.generate(queryText)
                Log.d("MECH_BRAIN", "Ответ сгенерирован, длина: ${response.length}")
                
                runOnUiThread {
                    statusTextView.text = "НЕО: ГОТОВ"
                    progressBar.visibility = View.GONE
                    try {
                        brainResponseFile.writeText(response)
                        Log.d("MECH_BRAIN", "Ответ записан в файл")
                    } catch (e: Exception) {
                        Log.e("MECH_BRAIN", "Ошибка записи ответа: ${e.message}")
                    }
                }
                
                brainQueryFile.writeText("")
                
            } catch (e: Exception) {
                Log.e("MECH_BRAIN", "Ошибка генерации ответа: ${e.message}")
                runOnUiThread {
                    statusTextView.text = "НЕО: ОШИБКА"
                    progressBar.visibility = View.GONE
                    try {
                        brainResponseFile.writeText("Ошибка: ${e.message}")
                    } catch (ex: Exception) {
                        Log.e("MECH_BRAIN", "Ошибка записи ответа: ${ex.message}")
                    }
                }
            }
        }.start()
    }

    companion object {
        init {
            try {
                System.loadLibrary("rnllama")
                Log.d("MECH_BRAIN", "JNI библиотека rnllama успешно загружена")
            } catch (e: Exception) {
                Log.e("MECH_BRAIN", "Ошибка линковки JNI библиотеки: ${e.message}")
                try {
                    System.loadLibrary("llama")
                    Log.d("MECH_BRAIN", "JNI библиотека llama загружена как запасной вариант")
                } catch (e2: Exception) {
                    Log.e("MECH_BRAIN", "Ошибка загрузки запасной библиотеки: ${e2.message}")
                }
            }
        }
    }
}
