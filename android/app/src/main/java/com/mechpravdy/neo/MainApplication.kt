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

        override fun getPackages(): List<ReactPackage> {
            val packagesList = mutableListOf<ReactPackage>(
                com.facebook.react.shell.MainReactPackage()
            )
            try {
                // Динамически вытаскиваем фабричный инстанс пакета по любому из двух путей
                val packageClass = try {
                    Class.forName("com.rnllama.LlamaPackage")
                } catch (e: Exception) {
                    Class.forName("com.pocketpalai.llama.LlamaPackage")
                }
                val packageInstance = packageClass.getConstructor().newInstance() as ReactPackage
                packagesList.add(packageInstance)
            } catch (e: Exception) {
                android.util.Log.e("MECH_SYSTEM", "Ошибка загрузки пакета рефлексией: ${e.message}")
            }
            return packagesList
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
