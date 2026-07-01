package com.mechpravdy.neo

import android.content.Context
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.Promise
import com.pocketpalai.llama.RNLlamaModule

object LlamaJNI {
    private const val TAG = "MECH_LAMA"
    private var llamaModule: RNLlamaModule? = null
    private var contextId: String? = null
    private var isPredicting = false

    // Проверка, сидит ли модель в памяти процесса
    fun isModelLoaded(): Boolean = contextId != null

    // Загрузка модели строго по спецификации RNLlamaModule
    fun loadModel(androidContext: Context, modelPath: String, contextSize: Int): Boolean {
        try {
            val reactContext = ReactApplicationContext(androidContext)
            llamaModule = RNLlamaModule(reactContext)

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

            var isSuccess = false
            val latch = java.util.concurrent.CountDownLatch(1)

            // Вызов заводского метода инициализации контекста
            llamaModule?.initContext(params, object : Promise {
                override fun resolve(result: Any?) {
                    val map = result as? ReadableMap
                    contextId = map?.getString("context")
                    isSuccess = contextId != null
                    Log.d(TAG, "✅ Модель успешно загружена. ID: $contextId")
                    latch.countDown()
                }
                override fun reject(code: String?, message: String?, throwable: Throwable?) {
                    Log.e(TAG, "❌ Ошибка C++ ядра: $message")
                    contextId = null
                    latch.countDown()
                }
                override fun reject(message: String?) { latch.countDown() }
                override fun reject(code: String?, message: String?) { latch.countDown() }
                override fun reject(code: String?, throwable: Throwable?) { latch.countDown() }
                override fun reject(throwable: Throwable?) { latch.countDown() }
            })

            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            return isSuccess
        } catch (e: Throwable) {
            Log.e(TAG, "Критический сбой JNI моста: ${e.message}")
            return false
        }
    }

    // Синхронизированная генерация через Promise-колбэки PocketPal
    fun generate(prompt: String): String {
        val currentCtx = contextId ?: return "(Ошибка: Модель не загружена)"
        val module = llamaModule ?: return "(Ошибка: Модуль мертв)"
        
        val responseBuilder = java.lang.StringBuilder()
        val latch = java.util.concurrent.CountDownLatch(1)

        val params = WritableNativeMap().apply {
            putString("context", currentCtx)
            putString("prompt", prompt)
            putInt("n_predict", 512)
        }

        isPredicting = true

        module.completion(params, object : Promise {
            override fun resolve(result: Any?) {
                val map = result as? ReadableMap
                if (map != null && map.hasKey("text")) {
                    responseBuilder.append(map.getString("text"))
                }
                isPredicting = false
                latch.countDown()
            }
            override fun reject(code: String?, message: String?, throwable: Throwable?) {
                isPredicting = false
                latch.countDown()
            }
            override fun reject(message: String?) { latch.countDown() }
            override fun reject(code: String?, message: String?) { latch.countDown() }
            override fun reject(code: String?, throwable: Throwable?) { latch.countDown() }
            override fun reject(throwable: Throwable?) { latch.countDown() }
        })

        latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        return responseBuilder.toString().trim()
    }
}
