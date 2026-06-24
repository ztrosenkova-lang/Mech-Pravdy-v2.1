package com.mechpravdy.neo

import android.os.Bundle
import android.os.FileObserver
import android.os.Process
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import java.io.File

class BrainFloatingWindow : ReactActivity() {

    private var queryObserver: FileObserver? = null

    override fun getMainComponentName(): String = "FloatingBrain"

    override fun createReactActivityDelegate(): ReactActivityDelegate =
        DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // В onCreate ничего тяжелого не делаем, чтобы операционная система успела выдать права процессу
    }

    // ИСПРАВЛЕНО: Переносим инициализацию моста в onResume (когда процесс уже легально запущен и одобрен системой)
    override fun onResume() {
        super.onResume()
        
        if (queryObserver == null) {
            try {
                val queryFile = File(filesDir, "brain_query.txt")
                val responseFile = File(filesDir, "brain_response.txt")

                if (!queryFile.exists()) queryFile.createNewFile()
                if (!responseFile.exists()) responseFile.createNewFile()

                queryObserver = object : FileObserver(queryFile.path, CLOSE_WRITE) {
                    override fun onEvent(event: Int, path: String?) {
                        try {
                            val queryText = queryFile.readText().trim()
                            if (queryText.isNotBlank()) {
                                queryFile.writeText("") // Очищаем запрос

                                Thread {
                                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                                    try {
                                        // Безопасный вызов JNI с проверкой
                                        val aiResponse = try {
                                            if (LlamaJNI.isModelLoaded()) {
                                                LlamaJNI.generate(queryText, 512)
                                            } else {
                                                "[МОЗГ] Ошибка: Локальная модель GGUF не загружена."
                                            }
                                        } catch (t: Throwable) {
                                            "[JNI ERROR] Ошибка вызова нативного метода: ${t.message}"
                                        }

                                        responseFile.writeText(aiResponse)
                                    } catch (e: Exception) {
                                        try { responseFile.writeText("[ОШИБКА МОЗГА] ${e.message}") } catch (_: Exception) {}
                                    }
                                }.start()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MECH_BRAIN", "Ошибка чтения файла воркфлоу: ${e.message}")
                        }
                    }
                }
                queryObserver?.startWatching()
                android.util.Log.d("MECH_BRAIN", "Нативный мост безопасности успешно запущен в фазе OnResume.")
            } catch (e: Exception) {
                android.util.Log.e("MECH_BRAIN", "Не удалось запустить мост: ${e.message}")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // При сворачивании окна временно гасим слежку, чтобы не злить систему безопасности Android
        queryObserver?.stopWatching()
        queryObserver = null
    }

    companion object {
        init {
            try {
                System.loadLibrary("llama")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("MECH_BRAIN", "Нативная библиотека не слинковалась: ${e.message}")
            }
        }
    }
}
