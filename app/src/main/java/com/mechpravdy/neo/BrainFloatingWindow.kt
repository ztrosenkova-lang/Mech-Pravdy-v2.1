package com.mechpravdy.neo

import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.facebook.react.ReactActivity

class BrainFloatingWindow : ReactActivity() {

    override fun getMainComponentName(): String = "FloatingBrain"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Проверяем разрешение перед выводом окна, чтобы не обрушить процесс
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish() // Закрываемся, пока пользователь не выдаст права
            return
        }

        setupFloatingWindow()
    }

    private fun setupFloatingWindow() {
        window?.apply {
            setFormat(PixelFormat.TRANSLUCENT)
            
            // Выравнивание: строго ПОСЕРЕДИНЕ ВЕРХНЕЙ ПОЛОВИНЫ экрана
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            
            val params = attributes
            // Переводим 250x350 dp в физические пиксели устройства
            params.width = (250 * resources.displayMetrics.density).toInt()
            params.height = (350 * resources.displayMetrics.density).toInt()
            
            params.x = 0
            // Сдвиг сверху, чтобы окно встало аккурат посередине верхней половины дисплея
            params.y = (150 * resources.displayMetrics.density).toInt() 
            
            // Системные флаги для работы поверх всех окон без перехвата клавиатуры основного чата
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            params.type = layoutFlag
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                           WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            
            attributes = params

            // Полупрозрачный белый фон, зеленый контур и закругленные края
            val borderDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#D9FFFFFF")) // Белый фон (85% непрозрачности)
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor("#1A8A2E")) // Зеленый контур
                cornerRadius = 16 * resources.displayMetrics.density // Скругление углов
            }
            setBackgroundDrawable(borderDrawable)
        }
    }
}
