package com.mechpravdy.neo

import android.content.Context
import android.util.Log
import io.github.ljcamargo.llamacppkt.LlamaModel

object LlamaJNI {
    private const val TAG = "MECH_LAMA_NATIVE"
    private var nativeModel: LlamaModel? = null
    var isPredicting = false

    fun isModelLoaded(): Boolean = nativeModel != null

    fun unloadModel() {
        try {
            nativeModel?.close()
        } catch (_: Exception) {}
        nativeModel = null
        Log.d(TAG, "♻ Нативная С++ модель полностью выгружена из ОЗУ.")
    }

    fun loadModel(androidContext: Context, modelPath: String, contextSize: Int): Boolean {
        try {
            unloadModel() // Принудительно чистим ОЗУ перед новой загрузкой
            Log.d(TAG, "Загрузка весов через чистый C++: $modelPath")
            
            // Жесткий нативный запуск модели в один вызов, без зависающих JS-колбэков!
            nativeModel = LlamaModel(modelPath, contextSize)
            return nativeModel != null
        } catch (e: Throwable) {
            Log.e(TAG, "Критический сбой загрузки нативного ядра llama.cpp: ${e.message}")
            return false
        }
    }

    fun generate(prompt: String): String {
        val model = nativeModel ?: return "(Ошибка: Нативный Мозг не загружен)"
        isPredicting = true
        return try {
            val responseBuilder = java.lang.StringBuilder()
            
            // Нативная потоковая генерация токенов напрямую в Kotlin
            model.generate(prompt).forEach { token ->
                responseBuilder.append(token)
            }
            
            responseBuilder.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инференса: ${e.message}")
            "(Ошибка вычислений)"
        } finally {
            isPredicting = false
        }
    }
}
