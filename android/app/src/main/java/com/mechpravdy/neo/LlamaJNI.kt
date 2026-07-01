package com.mechpravdy.neo

import android.content.Context
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.Promise
// ИСПРАВЛЕНО: Правильный заводской импорт модуля из .aar библиотеки
import com.rnllama.RNLlamaModule

object LlamaJNI {
    private const val TAG = "MECH_LAMA"
    private var llamaModule: RNLlamaModule? = null
    private var contextId: String? = null
    var isPredicting = false

    fun isModelLoaded(): Boolean = contextId != null

    // Метод выгрузки модели, который забыл робот
    fun unloadModel() {
        contextId = null
        llamaModule = null
        Log.d(TAG, "♻ Контекст модели успешно выгружен из ОЗУ.")
    }

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

            llamaModule?.initContext(params, object : Promise {
                override fun resolve(result: Any?) {
                    val map = result as? ReadableMap
                    contextId = map?.getString("context")
                    isSuccess = contextId != null
                    latch.countDown()
                }
                // ИСПРАВЛЕНО: Полная реализация всех методов Promise, чтоб не ругался компилятор
                override fun reject(code: String?, message: String?, throwable: Throwable?) { latch.countDown() }
                override fun reject(message: String?) { latch.countDown() }
                override fun reject(code: String?, message: String?) { latch.countDown() }
                override fun reject(code: String?, throwable: Throwable?) { latch.countDown() }
                override fun reject(throwable: Throwable?) { latch.countDown() }
                override fun reject(code: String?, message: String?, userInfo: WritableMap?) { latch.countDown() }
                override fun reject(code: String?, message: String?, throwable: Throwable?, userInfo: WritableMap?) { latch.countDown() }
            })

            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            return isSuccess
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

        module.completion(params, object : Promise {
            override fun resolve(result: Any?) {
                val map = result as? ReadableMap
                if (map != null && map.hasKey("text")) {
                    responseBuilder.append(map.getString("text"))
                }
                isPredicting = false
                latch.countDown()
            }
            // ИСПРАВЛЕНО: Полная реализация дефолтных методов Promise
            override fun reject(code: String?, message: String?, throwable: Throwable?) { isPredicting = false; latch.countDown() }
            override fun reject(message: String?) { isPredicting = false; latch.countDown() }
            override fun reject(code: String?, message: String?) { isPredicting = false; latch.countDown() }
            override fun reject(code: String?, throwable: Throwable?) { isPredicting = false; latch.countDown() }
            override fun reject(throwable: Throwable?) { isPredicting = false; latch.countDown() }
            override fun reject(code: String?, message: String?, userInfo: WritableMap?) { isPredicting = false; latch.countDown() }
            override fun reject(code: String?, message: String?, throwable: Throwable?, userInfo: WritableMap?) { isPredicting = false; latch.countDown() }
        })

        latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        return responseBuilder.toString().trim()
    }
}
