package com.mechpravdy.neo

import android.content.Context
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.WritableNativeMap
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object LlamaJNI {
    // Хранилище живого контекста React Native моста
    var reactContext: com.facebook.react.bridge.ReactContext? = null

    private const val TAG = "LlamaJNI"
    private var contextPtr: Long = 0L
    private var isLibraryLoaded = false
    private var isModelLoaded = false

    // ===== НАТИВНЫЕ МЕТОДЫ (ИЗ LlamaContext.java) =====
    private external fun initContext(
        params: ReadableMap,
        loadProgressCallback: Any?
    ): WritableMap

    private external fun doCompletion(
        contextPtr: Long,
        params: ReadableMap,
        partialCallback: Any?
    ): WritableMap

    private external fun stopCompletion(contextPtr: Long)
    private external fun freeContext(contextPtr: Long)
    private external fun isPredicting(contextPtr: Long): Boolean
    private external fun interruptLoad(contextPtr: Long)

    init {
        try {
            System.loadLibrary("rnllama")
            isLibraryLoaded = true
            Log.d(TAG, "✅ Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ Failed to load native library: ${e.message}")
            isLibraryLoaded = false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error: ${e.message}")
            isLibraryLoaded = false
        }
    }

    fun isLoaded(): Boolean = isLibraryLoaded

    // ИСПРАВЛЕНО: добавлен androidContext и contextSize
    fun loadModel(androidContext: Context, modelPath: String, contextSize: Int): Boolean {
        if (!isLibraryLoaded) {
            Log.e(TAG, "❌ Native library not loaded")
            return false
        }

        val file = File(modelPath)
        if (!file.exists()) {
            Log.e(TAG, "❌ Model file not found: $modelPath")
            return false
        }

        return try {
            // Сохраняем контекст в глобальную переменную для использования в нативных методах
            if (androidContext is com.facebook.react.bridge.ReactContext) {
                reactContext = androidContext
                Log.d(TAG, "✅ ReactContext сохранён")
            } else {
                Log.w(TAG, "⚠️ Передан не ReactContext, использование может быть ограничено")
                reactContext = null
            }

            // ИСПРАВЛЕНО: Отключаем падение по GPU слоям, переводим Gemma 2 на CPU
            // ИСПРАВЛЕНО: use_mlock = false для предотвращения убийства процесса менеджером памяти
            // ИСПРАВЛЕНО: используем WritableNativeMap вместо Arguments.createMap()
            val params = WritableNativeMap().apply {
                putString("model", modelPath)
                putBoolean("use_mlock", false)              // ← ИСПРАВЛЕНО: отключаем блокировку памяти
                putBoolean("embedding", false)
                putBoolean("use_mmap", true)
                putInt("n_ctx", contextSize)                // ← ИСПРАВЛЕНО: теперь будет работать и 4096, и авто-ноль!
                putInt("n_gpu_layers", 0)                   // ← CPU только
                putBoolean("no_gpu_devices", true)          // ← GPU отключён
            }

            // Передаём androidContext в инициализатор PocketPal через params
            // (нативная сторона получит контекст через ReactContext)
            val result = initContext(params, null)
            
            // ИСПРАВЛЕНО: Безопасный разбор без жесткого ClassCastException к ReadableMap
            val contextId = try {
                if (result.hasKey("context")) {
                    // Проверяем тип данных (в PocketPal это может быть либо Double, либо String)
                    when (result.getType("context")) {
                        ReadableType.Number -> result.getDouble("context").toLong()
                        ReadableType.String -> result.getString("context")?.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                } else 0L
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка парсинга указателя контекста: ${e.message}")
                0L
            }

            contextPtr = contextId

            if (contextPtr == 0L) {
                Log.e(TAG, "❌ Invalid context pointer (Указатель равен 0)")
                return false
            }

            isModelLoaded = true
            Log.d(TAG, "✅ Model loaded: $modelPath with contextSize=$contextSize")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading model: ${e.message}")
            false
        }
    }

    fun generate(prompt: String, maxTokens: Int = 512): String {
        if (!isModelLoaded || contextPtr == 0L) {
            return "Ошибка: модель не загружена"
        }

        return try {
            // Проверяем, не занят ли контекст
            if (isPredicting(contextPtr)) {
                return "Ошибка: модель занята"
            }

            // ИСПРАВЛЕНО: Оптимальные параметры пакетов под 6 ГБ ОЗУ
            // ИСПРАВЛЕНО: используем WritableNativeMap вместо Arguments.createMap()
            val params = WritableNativeMap().apply {
                putString("prompt", prompt)
                putInt("n_predict", maxTokens)
                putDouble("temperature", 0.7)
                putDouble("top_p", 0.9)
                putInt("top_k", 40)
                putInt("n_threads", 4)      // ← ровно 4 производительных ядра
                putInt("n_batch", 128)      // ← урезаем до 128 для разгрузки шины памяти
                putBoolean("emit_partial_completion", false)
            }

            // Вызываем doCompletion
            val result = doCompletion(contextPtr, params, null)
            
            // Получаем текст ответа — безопасный парсинг
            val text = try {
                if (result.hasKey("text")) {
                    when (result.getType("text")) {
                        ReadableType.String -> result.getString("text")
                        else -> null
                    }
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка парсинга ответа: ${e.message}")
                null
            }

            if (text.isNullOrEmpty()) {
                "(пустой ответ)"
            } else {
                text
            }
        } catch (e: Exception) {
            "Ошибка генерации: ${e.message}"
        }
    }

    fun unloadModel() {
        try {
            if (contextPtr != 0L) {
                if (isPredicting(contextPtr)) {
                    stopCompletion(contextPtr)
                }
                freeContext(contextPtr)
                contextPtr = 0L
                isModelLoaded = false
                Log.d(TAG, "✅ Model unloaded")
            }
            // Очищаем ReactContext при выгрузке модели
            reactContext = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error unloading model: ${e.message}")
        }
    }

    fun isModelLoaded(): Boolean = isModelLoaded
}
