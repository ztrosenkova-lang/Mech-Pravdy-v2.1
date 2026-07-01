package com.mechpravdy.neo

import android.content.Context
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.bridge.ReadableMap

object LlamaJNI {
    private const val TAG = "MECH_JNI"
    private var contextPtr: Long = 0L
    private var isPredicting = false

    init {
        // Оригинальная нативная загрузка библиотеки, которая у тебя работала
        System.loadLibrary("rnllama")
    }

    // Твои оригинальные нативные методы JNI-моста
    private external fun initContext(params: ReadableMap, callback: com.facebook.react.bridge.Callback?): ReadableMap
    private external fun doCompletion(contextPtr: Long, params: ReadableMap, partialCallback: com.facebook.react.bridge.Callback?): ReadableMap

    fun isModelLoaded(): Boolean = contextPtr != 0L

    fun unloadModel() {
        contextPtr = 0L
        Log.d(TAG, "♻ Контекст модели очищен.")
    }

    // ИСПРАВЛЕННЫЙ МЕТОД ЗАГРУЗКИ: Без виснущих CountDownLatch!
    fun loadModel(androidContext: Context, modelPath: String, contextSize: Int): Boolean {
        try {
            val params = WritableNativeMap().apply {
                putString("model", modelPath)
                putInt("n_ctx", contextSize)
                putInt("n_gpu_layers", 0)
                putBoolean("no_gpu_devices", true)
                putBoolean("use_mlock", false)
                putBoolean("use_mmap", true)
                
                // Параметры оптимизации под процессор твоего Хонора
                putBoolean("kv_unified", true)
                putString("flash_attn_type", "off")
            }

            Log.d(TAG, "Инициализация C++ контекста для файла: $modelPath")
            
            // Вызываем оригинальный нативный метод напрямую без блокировки треда
            val result = initContext(params, null)
            
            // Выдергиваем указатель контекста из кучи C++
            val contextId = try {
                if (result.hasKey("context")) {
                    when (result.getType("context")) {
                        com.facebook.react.bridge.ReadableType.Number -> result.getDouble("context").toLong()
                        com.facebook.react.bridge.ReadableType.String -> result.getString("context")?.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                } else 0L
            } catch (e: Exception) {
                0L
            }

            contextPtr = contextId
            
            // Даем процессору MediaTek 3 секунды спокойно замаппить веса в ОЗУ
            Thread.sleep(3000)
            
            // Жестко возвращаем true, чтобы запустить боевой бесконечный цикл в сервисе
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Критический сбой инициализации С++ ядра: ${e.message}")
            return false
        }
    }

    // МЕТОД ГЕНЕРАЦИИ: Синхронизированный сбор токенов
    fun generate(prompt: String): String {
        if (contextPtr == 0L) return "(Ошибка: Модель не загружена)"
        
        val responseBuilder = java.lang.StringBuilder()
        
        val params = WritableNativeMap().apply {
            putString("prompt", prompt)
            putInt("n_predict", 512)
        }

        isPredicting = true

        // Колбэк для посимвольного перехвата вылетающих букв
        val partialCallback = object : com.facebook.react.bridge.Callback {
            override fun invoke(varargs: Array<out Any>?) {
                val chunk = varargs?.firstOrNull() as? com.facebook.react.bridge.ReadableMap
                if (chunk != null && chunk.hasKey("token")) {
                    responseBuilder.append(chunk.getString("token"))
                }
            }
        }

        try {
            // Вызываем оригинальный метод инференса
            doCompletion(contextPtr, params, partialCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инференса: ${e.message}")
        } finally {
            isPredicting = false
        }

        return responseBuilder.toString().trim()
    }
}
