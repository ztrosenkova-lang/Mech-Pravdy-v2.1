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

        // ИСПРАВЛЕНО: возвращаем фабричный путь пакета библиотеки rnllama
        override fun getPackages(): List<ReactPackage> {
            return listOf(
                com.facebook.react.shell.MainReactPackage(),
                com.rnllama.LlamaPackage()
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        SoLoader.init(this, false)
        try {
            reactNativeHost.reactInstanceManager.createReactContextInBackground()
            android.util.Log.d("MECH_SYSTEM", "🚀 Нативный С++ мост JNI успешно развернут.")
        } catch (e: Exception) {
            android.util.Log.e("MECH_SYSTEM", "❌ Критический сбой запуска моста: ${e.message}")
        }
    }
}
