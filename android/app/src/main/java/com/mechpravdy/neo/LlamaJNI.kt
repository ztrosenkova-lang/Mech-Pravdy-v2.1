package com.mechpravdy.neo

import android.content.Context
import android.util.Log
import io.github.ljcamargo.llamacppkt.LlamaModel // Импорт из новой чистой либы

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
        Log.d(TAG, "♻ Нативная модель выгружена из памяти.")
    }

    fun loadModel(androidContext: Context, modelPath: String, contextSize: Int): Boolean {
        try {
            unloadModel() // Чистим старый контекст перед загрузкой
            Log.d(TAG, "Загрузка весов через чистый C++: $modelPath")
            
            // Нативный запуск модели в один вызов, без зависающих колбэков!
            nativeModel = LlamaModel(modelPath, contextSize)
            
            return nativeModel != null
        } catch (e: Throwable) {
            Log.e(TAG, "Критический сбой загрузки нативного ядра: ${e.message}")
            return false
        }
    }

    fun generate(prompt: String): String {
        val model = nativeModel ?: return "(Ошибка: Нативный Мозг не загружен)"
        isPredicting = true
        return try {
            val responseBuilder = java.lang.StringBuilder()
            
            // Потоковая нативная генерация токенов напрямую в Kotlin Flow / Sequence
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
