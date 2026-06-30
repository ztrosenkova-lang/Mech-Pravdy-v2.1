package com.mechpravdy.neo

import android.content.Context
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object LlamaJNI {
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

    fun loadModel(modelPath: String, nCtx: Int = 2048): Boolean {
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
            // ИСПРАВЛЕНО: Отключаем падение по GPU слоям, переводим Gemma 2 на CPU
            val params = Arguments.createMap().apply {
                putString("model", modelPath)
                putBoolean("use_mlock", true)
                putBoolean("embedding", false)
                putInt("n_ctx", nCtx)
                putInt("n_gpu_layers", 0)        // ← CPU только
                putBoolean("no_gpu_devices", true) // ← GPU отключён
            }

            // Вызываем initContext
            val result = initContext(params, null)
            
            // Получаем указатель на контекст — кастуем к ReadableMap
            val ctxStr = (result as? com.facebook.react.bridge.ReadableMap)?.getString("context")
            if (ctxStr.isNullOrEmpty()) {
                Log.e(TAG, "❌ Failed to get context pointer")
                return false
            }

            contextPtr = ctxStr.toLongOrNull() ?: 0L
            if (contextPtr == 0L) {
                Log.e(TAG, "❌ Invalid context pointer")
                return false
            }

            isModelLoaded = true
            Log.d(TAG, "✅ Model loaded: $modelPath")
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
            val params = Arguments.createMap().apply {
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
            
            // Получаем текст ответа — кастуем к ReadableMap
            val text = (result as? com.facebook.react.bridge.ReadableMap)?.getString("text")
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
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error unloading model: ${e.message}")
        }
    }

    fun isModelLoaded(): Boolean = isModelLoaded
}
