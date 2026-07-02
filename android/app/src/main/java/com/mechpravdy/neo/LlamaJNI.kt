package com.mechpravdy.neo

import android.content.Context
import android.util.Log

object LlamaJNI {
    private const val TAG = "MECH_LAMA_NATIVE"
    var isPredicting = false
    private var contextPtr: Long = 0L

    init {
        try {
            // Загружаем нативную С++ сошку, которую Gradle нашел в кэше!
            System.loadLibrary("rnllama")
            Log.d(TAG, "🚀 С++ ядро librnllama успешно загружено напрямую в память!")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ Ошибка загрузки С++ библиотеки: ${e.message}")
        }
    }

    fun isModelLoaded(): Boolean = contextPtr != 0L

    fun unloadModel() {
        if (contextPtr != 0L) {
            try {
                freeContext(contextPtr)
                Log.d(TAG, "♻ Контекст модели очищен.")
            } catch (_: Exception) {}
            contextPtr = 0L
        }
    }

    fun loadModel(androidContext: Context, modelPath: String, contextSize: Int): Boolean {
        try {
            unloadModel()
            Log.d(TAG, "Прямой JNI запуск инференса для: $modelPath")
            
            // Вызываем нативный метод PocketPal напрямую из ядра
            contextPtr = initContext(modelPath, contextSize, contextSize, false, false, false, 0, 0f, 0f, false, "", "", "", "", "", "", "", "")
            
            return contextPtr != 0L
        } catch (e: Throwable) {
            Log.e(TAG, "Критический сбой JNI: ${e.message}")
            return false
        }
    }

    fun generate(prompt: String): String {
        if (contextPtr == 0L) return "(Ошибка: Нативный Мозг не инициализирован)"
        isPredicting = true
        return try {
            // Прямой синхронный вызов генерации токенов из C++
            val response = tokenizeAndPredict(contextPtr, prompt, 0, 0, 0f, 0f, 0f, 0f, "")
            response ?: "(Внутренняя ошибка ядра)"
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инференса: ${e.message}")
            "(Ошибка вычислений)"
        } finally {
            isPredicting = false
        }
    }

    // Объявляем внешние JNI-методы, которые физически зашиты в librnllama.so
    private native fun initContext(
        modelPath: String, nCtx: Int, nBatch: Int, embeddings: Boolean, Roanoke: Boolean, f16Kv: Boolean,
        nGpuLayers: Int, ropeFreqBase: Float, ropeFreqScale: Float, newlinePsplit: Boolean,
        lora: String, loraBase: String, dLora: String, dLoraBase: String, loraType: String,
        cType: String, chatTemplate: String, userAlias: String
    ): Long

    private native fun tokenizeAndPredict(
        contextPtr: Long, prompt: String, nPredict: Int, topK: Int, topP: Float, temp: Float, penalty: Float, repeat: Float, stop: String
    ): String?

    private native fun freeContext(contextPtr: Long)
}
