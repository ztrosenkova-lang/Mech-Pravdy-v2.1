package com.mechpravdy.neo

import android.content.Context
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.Promise
import com.pocketpalai.llama.LlamaModule

object LlamaJNI {
    private const val TAG = "MECH_LAMA"
    private var llamaModule: LlamaModule? = null
    private var contextId: String? = null
    var isPredicting = false

    fun isModelLoaded(): Boolean = contextId != null

    fun unloadModel() {
        contextId = null
        llamaModule = null
        Log.d(TAG, "♻ Контекст модели успешно выгружен из ОЗУ.")
    }

    // Фабричный пустой Promise, чтобы убрать зависания потоков
    private val emptyPromise = object : Promise {
        override fun resolve(result: Any?) {
            val map = result as? ReadableMap
            contextId = map?.getString("context")
            Log.d(TAG, "✅ Модель успешно проинициализирована: $contextId")
        }
        override fun reject(code: String?, message: String?, throwable: Throwable?) {}
        override fun reject(message: String?) {}
        override fun reject(code: String?, message: String?) {}
        override fun reject(code: String?, throwable: Throwable?) {}
        override fun reject(throwable: Throwable?) {}
        override fun reject(code: String?, message: String?, userInfo: com.facebook.react.bridge.WritableMap?) {}
        override fun reject(code: String?, message: String?, throwable: Throwable?, userInfo: com.facebook.react.bridge.WritableMap?) {}
    }

    fun loadModel(androidContext: Context, modelPath: String, contextSize: Int): Boolean {
        try {
            val reactContext = ReactApplicationContext(androidContext)
            llamaModule = LlamaModule(reactContext)

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

            // Вызываем нативный метод инициализации контекста напрямую
            llamaModule?.initContext(params, emptyPromise)
            
            // Даем С++ ядру 3 секунды на маппинг файла
            Thread.sleep(3000)
            
            // Если ID контекста создался — модель успешно села в ОЗУ
            return contextId != null
        } catch (e: Throwable) {
            Log.e(TAG, "Критический сбой JNI моста: ${e.message}")
            return false
        }
    }

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

        val completionPromise = object : Promise {
            override fun resolve(result: Any?) {
                val map = result as? ReadableMap
                if (map != null && map.hasKey("text")) {
                    responseBuilder.append(map.getString("text"))
                }
                isPredicting = false
                latch.countDown()
            }
            override fun reject(code: String?, message: String?, throwable: Throwable?) { isPredicting = false; latch.countDown() }
            override fun reject(message: String?) { isPredicting = false; latch.countDown() }
            override fun reject(code: String?, message: String?) { isPredicting = false; latch.countDown() }
            override fun reject(code: String?, throwable: Throwable?) { isPredicting = false; latch.countDown() }
            override fun reject(throwable: Throwable?) { isPredicting = false; latch.countDown() }
            override fun reject(code: String?, message: String?, userInfo: com.facebook.react.bridge.WritableMap?) { isPredicting = false; latch.countDown() }
            override fun reject(code: String?, message: String?, throwable: Throwable?, userInfo: com.facebook.react.bridge.WritableMap?) { isPredicting = false; latch.countDown() }
        }

        // Вызываем генерацию токенов
        module.completion(params, completionPromise)

        latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        return responseBuilder.toString().trim()
    }
}
