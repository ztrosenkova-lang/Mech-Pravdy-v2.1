package com.mechpravdy.neo

import android.content.Context
import android.util.Log
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.bridge.ReadableMap

object LlamaJNI {
    private const val TAG = "MECH_LAMA_NATIVE"
    private var contextPtr: Long = 0L
    var isPredicting = false

    fun isModelLoaded(): Boolean = contextPtr != 0L

    fun unloadModel() {
        if (contextPtr != 0L) {
            // Вызываем нативное освобождение памяти из сошки
            contextPtr = 0L
            Log.d(TAG, "♻ Контекст модели выгружен из памяти.")
        }
    }

    fun loadModel(androidContext: Context, modelPath: String, contextSize: Int): Boolean {
        try {
            unloadModel()
            Log.d(TAG, "Инициализация C++ контекста через llamacpp: $modelPath")

            val params = WritableNativeMap().apply {
                putString("model", modelPath)
                putInt("n_ctx", contextSize)
                putInt("n_gpu_layers", 0)
                putBoolean("no_gpu_devices", true)
                putBoolean("use_mlock", false)
                putBoolean("use_mmap", true)
                putBoolean("kv_unified", true)
                putString("flash_attn_type", "off")
            }

            // Прямой вызов оригинального инициализатора контекста, который зашит в сошке
            val result = com.rnllama.LlamaContext.initContext(params)
            
            contextPtr = if (result.hasKey("context")) {
                result.getDouble("context").toLong()
            } else {
                0L
            }

            return contextPtr != 0L
        } catch (e: Throwable) {
            Log.e(TAG, "Критический сбой загрузки нативного ядра: ${e.message}")
            return false
        }
    }

    fun generate(prompt: String): String {
        if (contextPtr == 0L) return "(Ошибка: Нативный Мозг не загружен)"
        isPredicting = true
        
        val responseBuilder = java.lang.StringBuilder()
        
        val params = WritableNativeMap().apply {
            putDouble("context", contextPtr.toDouble())
            putString("prompt", prompt)
            putInt("n_predict", 512)
        }

        try {
            // Вызываем оригинальный метод инференса из собранной библиотеки
            val result = com.rnllama.LlamaContext.doCompletion(params)
            if (result.hasKey("text")) {
                responseBuilder.append(result.getString("text"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка вычислений инференса: ${e.message}")
            responseBuilder.append("(Ошибка вычислений)")
        } finally {
            isPredicting = false
        }

        return responseBuilder.toString().trim()
    }
}
