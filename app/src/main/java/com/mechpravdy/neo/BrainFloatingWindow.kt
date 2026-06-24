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

    // Имя вашего главного React-компонента, зарегистрированного в JS-слое
    override fun getMainComponentName(): String = "FloatingBrain"

    override fun createReactActivityDelegate(): ReactActivityDelegate =
        DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Инициализируем пути к файлам связи в контексте изолированного процесса Мозга
        val queryFile = File(filesDir, "brain_query.txt")
        val responseFile = File(filesDir, "brain_response.txt")

        // 2. Создаем и запускаем нативный FileObserver для слежки за файлом запросов от "Меча"
        queryObserver = object : FileObserver(queryFile.path, CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                try {
                    val queryText = queryFile.readText().trim()
                    
                    if (queryText.isNotBlank()) {
                        // А. Очищаем файл запроса, давая знак "Мечу", что задача ушла в работу
                        queryFile.writeText("")

                        // Б. Переносим тяжелые вычисления ИИ в выделенный фоновый поток этого процесса
                        Thread {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                            try {
                                // Проверяем, готова ли нативная модель Llama
                                val aiResponse = if (LlamaJNI.isModelLoaded()) {
                                    // Запускаем генерацию ответа локальной GGUF модели
                                    LlamaJNI.generate(queryText, 512)
                                } else {
                                    "[МОЗГ] Ошибка: Локальная модель GGUF не загружена в память процесса."
                                }

                                // В. Записываем сгенерированный ответ. Наблюдатель в Мече мгновенно его выведет!
                                responseFile.writeText(aiResponse)
                                
                            } catch (e: Exception) {
                                val errorMsg = "[ОШИБКА МОЗГА] Сбой генерации: ${e.message}"
                                android.util.Log.e("MECH_BRAIN", errorMsg, e)
                                try { responseFile.writeText(errorMsg) } catch (_: Exception) {}
                            }
                        }.start()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MECH_BRAIN", "Ошибка обработки файлового моста: ${e.message}", e)
                }
            }
        }
        
        // Включаем слежку за файлом команд
        queryObserver?.startWatching()
        android.util.Log.d("MECH_BRAIN", "Нативный файловый мост 'Меч-Мозг' успешно инициализирован.")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Обязательно останавливаем наблюдение, чтобы не текла память Android-процесса
        queryObserver?.stopWatching()
    }

    // 3. Статическая загрузка C++ библиотек строго внутри изолированного процесса Мозга
    companion object {
        init {
            try {
                // Имя библиотеки должно точно совпадать с вашей скомпилированной .so (без lib и .so)
                System.loadLibrary("llama")
                android.util.Log.d("MECH_BRAIN", "libllama.so успешно загружена в процесс :brain_process")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("MECH_BRAIN", "Фатальный сбой JNI: библиотека libllama.so не найдена: ${e.message}")
            }
        }
    }
}
