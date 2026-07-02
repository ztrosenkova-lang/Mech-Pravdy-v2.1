package com.mechpravdy.neo

import android.content.Context
import android.util.Log
// Импортируем оригинальный нативный класс из успешно скомпилированной либы!
import io.github.ljcamargo.llamacppkt.LlamaModel

object LlamaJNI {
    private const val TAG = "MECH_LAMA_NATIVE"
    private var modelInstance: LlamaModel? = null
    var isPredicting = false

    fun isModelLoaded(): Boolean = modelInstance != null

    fun unloadModel() {
        try {
            modelInstance?.close()
        } catch (_: Exception) {}
        modelInstance = null
        Log.d(TAG, "♻ Нативный С++ контекст полностью очищен.")
    }

    fun loadModel(androidContext: Context, modelPath: String, contextSize: Int): Boolean {
        try {
            unloadModel() // Чистим ОЗУ
            Log.d(TAG, "Прямая аллокация тензоров через llamacpp-kotlin: $modelPath")
            
            // Заводской нативный вызов библиотеки Луиса Камарго!
            modelInstance = LlamaModel(modelPath, contextSize)
            return modelInstance != null
        } catch (e: Throwable) {
            Log.e(TAG, "Критический сбой загрузки нативного ядра: ${e.message}")
            return false
        }
    }

    fun generate(prompt: String): String {
        val model = modelInstance ?: return "(Ошибка: Нативный Мозг не инициализирован)"
        isPredicting = true
        return try {
            val responseBuilder = java.lang.StringBuilder()
            
            // Потоковый сбор токенов напрямую из С++ ядра в Kotlin Sequence!
            model.generate(prompt).forEach { token ->
                responseBuilder.append(token)
            }
            
            responseBuilder.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка вычислений инференса: ${e.message}")
            "(Ошибка вычислений ядра)"
        } finally {
            isPredicting = false
        }
    }
}
