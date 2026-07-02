package com.mechpravdy.neo

import android.app.Application
import android.util.Log

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("MECH_SYSTEM", "🚀 Чистый нативный старт экосистемы Меч Правды.")
    }
}
