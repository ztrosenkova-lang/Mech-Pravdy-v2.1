package com.mechpravdy.neo

import android.app.Application
import com.facebook.react.ReactApplication
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.defaults.DefaultReactNativeHost
import com.facebook.soloader.SoLoader

class MainApplication : Application(), ReactApplication {

    override val reactNativeHost: ReactNativeHost = object : DefaultReactNativeHost(this) {
        override fun getJSMainModuleName(): String = "index"
        override fun getUseDeveloperSupport(): Boolean = false

        // ТВОЙ ОРИГИНАЛЬНЫЙ РАБОЧИЙ МАССИВ ПАКЕТОВ
        override fun getPackages(): List<ReactPackage> {
            return listOf(
                com.facebook.react.shell.MainReactPackage(),
                // Заводской пакет инференса из подключенной .aar библиотеки PocketPal
                com.pocketpalai.llama.LlamaPackage()
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Чистая нативная инициализация загрузчика С++ библиотек
        SoLoader.init(this, false)
        try {
            // Принудительный прогрев С++ таблиц рантайма в памяти процесса
            reactNativeHost.reactInstanceManager.createReactContextInBackground()
            android.util.Log.d("MECH_SYSTEM", "🚀 Нативный С++ мост JNI успешно развернун.")
        } catch (e: Exception) {
            android.util.Log.e("MECH_SYSTEM", "❌ Критический сбой запуска моста: ${e.message}")
        }
    }
}
