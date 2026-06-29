package com.mechpravdy.neo

import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.facebook.react.ReactActivity
import java.io.File

class BrainFloatingWindow : ReactActivity() {

    private var queryObserver: FileObserver? = null
    private var modelLoaded = false
    private val brainQueryFile by lazy { File(filesDir, "brain_query.txt") }
    private val brainResponseFile by lazy { File(filesDir, "brain_response.txt") }

    override fun getMainComponentName(): String = "FloatingBrain"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Проверяем разрешение перед выводом окна, чтобы не обрушить процесс
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

        setupFloatingWindow()
        
        // Инициализируем файлы для обмена
        initCommunicationFiles()
        
        // Загружаем модель, если передан путь
        val modelPath = intent.getStringExtra("MODEL_PATH")
        if (modelPath != null && !modelPath.isEmpty()) {
            loadModel(modelPath)
        }
    }

    override fun onResume() {
        super.onResume()
        startQueryObserver()
    }

    override fun onPause() {
        super.onPause()
        // Останавливаем наблюдатель, но не разрываем соединение полностью
        queryObserver?.stopWatching()
    }

    override fun onDestroy() {
        super.onDestroy()
        queryObserver?.stopWatching()
        queryObserver = null
        
        // Выгружаем модель при уничтожении окна
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
        window?.apply {
            setFormat(PixelFormat.TRANSLUCENT)
            
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            
            val params = attributes
            params.width = (250 * resources.displayMetrics.density).toInt()
            params.height = (350 * resources.displayMetrics.density).toInt()
            
            params.x = 0
            params.y = (150 * resources.displayMetrics.density).toInt()
            
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            params.type = layoutFlag
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                           WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            
            attributes = params

            val borderDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#D9FFFFFF"))
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor("#1A8A2E"))
                cornerRadius = 16 * resources.displayMetrics.density
            }
            setBackgroundDrawable(borderDrawable)
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
                val result = LlamaJNI.loadModel(modelPath)
                if (result) {
                    modelLoaded = true
                    Log.d("MECH_BRAIN", "Модель успешно загружена")
                    
                    // Пишем в файл ответа, что модель загружена
                    runOnUiThread {
                        try {
                            brainResponseFile.writeText("Модель загружена. Я готов к диалогу.")
                        } catch (e: Exception) {
                            Log.e("MECH_BRAIN", "Ошибка записи статуса: ${e.message}")
                        }
                    }
                } else {
                    Log.e("MECH_BRAIN", "Ошибка загрузки модели")
                    runOnUiThread {
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
                // Проверяем, что файл действительно изменился
                if (event and (FileObserver.CLOSE_WRITE or FileObserver.MODIFY) == 0) {
                    return
                }

                try {
                    val queryText = brainQueryFile.readText().trim()
                    if (queryText.isEmpty()) return

                    Log.d("MECH_BRAIN", "Получен запрос: $queryText")

                    // Проверяем команду выгрузки
                    if (queryText == "COMMAND_UNLOAD_MODEL") {
                        handleUnloadModel()
                        return
                    }

                    // Обрабатываем обычный запрос
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
                if (modelLoaded) {
                    LlamaJNI.unloadModel()
                    modelLoaded = false
                    Log.d("MECH_BRAIN", "Модель выгружена по команде")
                    
                    runOnUiThread {
                        try {
                            brainResponseFile.writeText("Модель выгружена")
                        } catch (e: Exception) {
                            Log.e("MECH_BRAIN", "Ошибка записи статуса: ${e.message}")
                        }
                    }
                } else {
                    Log.d("MECH_BRAIN", "Модель уже выгружена")
                }
                
                // Очищаем файл запроса
                brainQueryFile.writeText("")
            } catch (e: Exception) {
                Log.e("MECH_BRAIN", "Ошибка выгрузки модели: ${e.message}")
                runOnUiThread {
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
                // Проверяем, загружена ли модель
                if (!modelLoaded) {
                    runOnUiThread {
                        try {
                            brainResponseFile.writeText("Модель не загружена. Подождите...")
                        } catch (e: Exception) {
                            Log.e("MECH_BRAIN", "Ошибка записи ответа: ${e.message}")
                        }
                    }
                    return@Thread
                }

                Log.d("MECH_BRAIN", "Генерация ответа для запроса: $queryText")
                
                // Вызываем JNI для генерации ответа
                val response = LlamaJNI.generate(queryText)
                Log.d("MECH_BRAIN", "Ответ сгенерирован, длина: ${response.length}")
                
                // Записываем ответ в файл
                runOnUiThread {
                    try {
                        brainResponseFile.writeText(response)
                        Log.d("MECH_BRAIN", "Ответ записан в файл")
                    } catch (e: Exception) {
                        Log.e("MECH_BRAIN", "Ошибка записи ответа: ${e.message}")
                    }
                }
                
                // Очищаем файл запроса
                brainQueryFile.writeText("")
                
            } catch (e: Exception) {
                Log.e("MECH_BRAIN", "Ошибка генерации ответа: ${e.message}")
                runOnUiThread {
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
                // Пытаемся загрузить альтернативную библиотеку, если основная не загрузилась
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
