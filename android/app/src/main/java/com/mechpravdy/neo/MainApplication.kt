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

        // ИСПРАВЛЕНО: Ручная передача пакета PocketPal без CLI-скриптов и PackageList!
        override fun getPackages(): List<ReactPackage> {
            return listOf(
                com.facebook.react.shell.MainReactPackage(),
                // Вшиваем пакет инференса напрямую из импортированной библиотеки PocketPal
                com.pocketpalai.llama.LlamaPackage() 
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Инициализируем SoLoader для загрузки .so библиотек в память процесса
        SoLoader.init(this, false)
        try {
            // Принудительно разворачиваем таблицы JNI на старте
            reactNativeHost.reactInstanceManager.createReactContextInBackground()
            android.util.Log.d("MECH_SYSTEM", "🚀 Нативный С++ мост JNI успешно развернут в памяти.")
        } catch (e: Exception) {
            android.util.Log.e("MECH_SYSTEM", "❌ Критический сбой запуска моста: ${e.message}")
        }
    }
}
