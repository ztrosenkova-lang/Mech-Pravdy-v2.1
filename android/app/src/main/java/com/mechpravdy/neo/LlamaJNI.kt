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

    // ===== ИСПРАВЛЕНО: Полностью переписан loadModel с параметрами PocketPal =====
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

            // СТРОГО ПО ИНСТРУКЦИИ POCKETPAL ДЛЯ ANDROID:
            val params = WritableNativeMap().apply {
                putString("model", modelPath)
                putInt("n_ctx", contextSize)
                putInt("n_gpu_layers", 0)               // Строго CPU-инференс для Хонора
                putBoolean("no_gpu_devices", true)
                putBoolean("use_mlock", false)          // Защита от убийства процесса системой MagicUI
                putBoolean("use_mmap", true)            // Чтение кусками с флешки (песочницы)
                putBoolean("embedding", false)
                
                // СТРОГО ПО ИНСТРУКЦИИ POCKETPAL ДЛЯ ANDROID:
                putBoolean("kv_unified", true)          // Объединяем KV-кэш в монолит
                putString("flash_attn_type", "off")     // Гасим Flash Attention во избежание краша
            }

            val result = initContext(params, null)
            
            // Безопасный разбор указателя контекста из кучи C++
            val contextId = try {
                if (result.hasKey("context")) {
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
        } catch (e: Throwable) {
            Log.e(TAG, "❌ Критический сбой инициализации С++ ядра: ${e.message}")
            false
        }
    }

    // ===== ИСПРАВЛЕНО: Полностью переписан generate с JNI-колбэком =====
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
            val params = WritableNativeMap().apply {
                putString("prompt", prompt)
                putInt("n_predict", maxTokens)
                putDouble("temperature", 0.7)
                putDouble("top_p", 0.9)
                putInt("top_k", 40)
                putInt("n_threads", 4)      // ← ровно 4 производительных ядра
                putInt("n_batch", 128)      // ← урезаем до 128 для разгрузки шины памяти
                putBoolean("emit_partial_completion", true)  // ← ВКЛЮЧАЕМ потоковую отдачу
            }

            // Создаем колбэк для перехвата токенов
            val responseBuilder = StringBuilder()
            val partialCallback = object : com.facebook.react.bridge.Callback {
                override fun invoke(varargs: Array<out Any>?) {
                    try {
                        val chunk = varargs?.firstOrNull() as? com.facebook.react.bridge.ReadableMap
                        if (chunk != null && chunk.hasKey("token")) {
                            responseBuilder.append(chunk.getString("token"))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Ошибка в колбэке токена: ${e.message}")
                    }
                }
            }

            // Передаем колбэк в C++ ядро
            doCompletion(contextPtr, params, partialCallback)

            // Ждем завершения генерации с таймаутом
            var attempts = 0
            val maxAttempts = 600  // 60 секунд при 100 мс на попытку
            while (isPredicting(contextPtr) && attempts < maxAttempts) {
                Thread.sleep(100)
                attempts++
            }

            if (attempts >= maxAttempts) {
                Log.w(TAG, "⚠️ Таймаут генерации, принудительная остановка")
                stopCompletion(contextPtr)
                return responseBuilder.toString().trim().ifEmpty { "(таймаут генерации)" }
            }

            responseBuilder.toString().trim().ifEmpty { "(пустой ответ)" }
        } catch (e: Throwable) {
            Log.e(TAG, "❌ Критическая ошибка генерации: ${e.message}")
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
