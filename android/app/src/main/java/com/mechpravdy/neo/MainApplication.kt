package com.mechpravdy.neo

import android.app.Application

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("MECH_SYSTEM", "🚀 Чистый нативный старт приложения.")
    }
}
