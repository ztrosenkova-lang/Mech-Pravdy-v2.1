package com.mechpravdy.neo

import android.app.Application
import com.facebook.react.PackageList
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
            return PackageList(this).packages
        }
    }

    override fun onCreate() {
        super.onCreate()
        SoLoader.init(this, false)
        try {
            reactNativeHost.reactInstanceManager.createReactContextInBackground()
            android.util.Log.d("MECH_SYSTEM", "🚀 Нативный С++ React-контур запущен")
        } catch (e: Exception) {
            android.util.Log.e("MECH_SYSTEM", "❌ Ошибка: ${e.message}")
        }
    }
}
