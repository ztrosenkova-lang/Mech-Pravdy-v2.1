package com.mechpravdy.neo

import android.content.Context
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.bridge.ReadableMap
import java.lang.reflect.Proxy

object LlamaJNI {
    private const val TAG = "MECH_LAMA"
    private var llamaModuleInstance: Any? = null
    private var contextId: String? = null
    var isPredicting = false

    fun isModelLoaded(): Boolean = contextId != null

    fun unloadModel() {
        contextId = null
        llamaModuleInstance = null
        Log.d(TAG, "♻ Контекст модели успешно выгружен из ОЗУ.")
    }

    // Динамическая сборка Promise через прокси для обхода проверок компилятора
    private fun createDynamicPromise(onSuccess: (ReadableMap?) -> Unit): Any {
        val promiseClass = Class.forName("com.facebook.react.bridge.Promise")
        return Proxy.newProxyInstance(
            promiseClass.classLoader,
            arrayOf(promiseClass)
        ) { _, method, args ->
            if (method.name == "resolve") {
                val result = args?.firstOrNull() as? ReadableMap
                onSuccess(result)
            }
            if (method.name == "reject") {
                Log.e(TAG, "⚠ Нативный метод отклонен.")
            }
            null
        }
    }

    fun loadModel(androidContext: Context, modelPath: String, contextSize: Int): Boolean {
        try {
            val reactContext = ReactApplicationContext(androidContext)
            
            // Находим класс модуля в памяти, как бы его ни обозвал робот
            val moduleClass = try {
                Class.forName("com.rnllama.RNLlamaModule")
            } catch (e: Exception) {
                Class.forName("com.pocketpalai.llama.LlamaModule")
            }

            val constructor = moduleClass.getConstructor(ReactApplicationContext::class.java)
            val instance = constructor.newInstance(reactContext)
            llamaModuleInstance = instance

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

            val dynamicPromise = createDynamicPromise { map ->
                contextId = map?.getString("context")
                isSuccess = contextId != null
                latch.countDown()
            }

            val initMethod = moduleClass.getMethod(
                "initContext", 
                com.facebook.react.bridge.ReadableMap::class.java, 
                Class.forName("com.facebook.react.bridge.Promise")
            )
            
            initMethod.invoke(instance, params, dynamicPromise)
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            return isSuccess
        } catch (e: Throwable) {
            Log.e(TAG, "Критический сбой рефлексии JNI: ${e.message}")
            return false
        }
    }

    fun generate(prompt: String): String {
        val instance = llamaModuleInstance ?: return "(Ошибка: Модель не загружена)"
        val currentCtx = contextId ?: return "(Ошибка: Контекст пуст)"
        
        val responseBuilder = java.lang.StringBuilder()
        val latch = java.util.concurrent.CountDownLatch(1)

        val params = WritableNativeMap().apply {
            putString("context", currentCtx)
            putString("prompt", prompt)
            putInt("n_predict", 512)
        }

        isPredicting = true

        try {
            val dynamicPromise = createDynamicPromise { map ->
                if (map != null && map.hasKey("text")) {
                    responseBuilder.append(map.getString("text"))
                }
                isPredicting = false
                latch.countDown()
            }

            val completionMethod = instance.javaClass.getMethod(
                "completion", 
                com.facebook.react.bridge.ReadableMap::class.java, 
                Class.forName("com.facebook.react.bridge.Promise")
            )

            completionMethod.invoke(instance, params, dynamicPromise)
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            isPredicting = false
            latch.countDown()
        }

        return responseBuilder.toString().trim()
    }
}
