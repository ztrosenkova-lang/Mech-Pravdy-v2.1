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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class BrainFloatingWindow : ReactActivity() {

    companion object {
        private const val WINDOW_WIDTH_DP = 250
        private const val WINDOW_HEIGHT_DP = 350
        private const val TOP_OFFSET_DP = 150 // Отступ сверху для размещения в верхней половине
        private var isFullScreen = false
        private var isDragging = false
    }

    override fun getMainComponentName(): String = "FloatingBrain"

    override fun createReactActivityDelegate(): ReactActivityDelegate {
        return object : DefaultReactActivityDelegate(
            this,
            mainComponentName,
            fabricEnabled
        ) {
            override fun getLaunchOptions(): Bundle? {
                return Bundle()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Проверка разрешения на оверлей перед настройкой окна
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
            return
        }

        setupFloatingWindow()
        setupDragging()
        setupFullScreenToggle()
    }

    private fun setupFloatingWindow() {
        window.apply {
            // Прозрачность Activity
            setFormat(PixelFormat.TRANSLUCENT)
            
            // Выравнивание по центру верхней половины экрана
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)

            val params = attributes
            
            // Размер окна в пикселях
            params.width = dpToPx(WINDOW_WIDTH_DP)
            params.height = dpToPx(WINDOW_HEIGHT_DP)
            
            // Горизонтальное центрирование через Gravity, поэтому X = 0
            params.x = 0
            // Отступ сверху для размещения ровно в верхней половине
            params.y = dpToPx(TOP_OFFSET_DP)

            // Тип окна в зависимости от версии Android
            params.type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // Флаги: окно не фокусируемое, размещается в пределах экрана
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            attributes = params

            // Стилизация: полупрозрачный белый фон + зеленая рамка + скругление
            val borderDrawable = GradientDrawable().apply {
                // Белый фон с ~85% непрозрачности (#D9FFFFFF)
                setColor(Color.parseColor("#D9FFFFFF"))
                // Зеленая рамка толщиной 2dp
                setStroke(dpToPx(2), Color.parseColor("#1A8A2E"))
                // Скругление углов 16dp
                cornerRadius = dpToPx(16).toFloat()
            }
            setBackgroundDrawable(borderDrawable)
        }
    }

    private fun setupDragging() {
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        var startRawX = 0f
        var startRawY = 0f
        var dragStartParamsX = 0
        var dragStartParamsY = 0

        rootView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    startRawX = event.rawX
                    startRawY = event.rawY
                    val params = window.attributes
                    dragStartParamsX = params.x
                    dragStartParamsY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - startRawX).toInt()
                    val deltaY = (event.rawY - startRawY).toInt()

                    if (kotlin.math.abs(deltaX) > 10 || kotlin.math.abs(deltaY) > 10) {
                        isDragging = true
                    }

                    if (isDragging) {
                        val params = window.attributes
                        params.x = (dragStartParamsX + deltaX).coerceAtLeast(0)
                        params.y = (dragStartParamsY + deltaY).coerceAtLeast(0)
                        window.attributes = params
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupFullScreenToggle() {
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        var lastClickTime = 0L

        rootView.setOnClickListener {
            if (isDragging) return@setOnClickListener
            
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime < 300) {
                toggleFullScreen()
            }
            lastClickTime = currentTime
        }
    }

    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        val params = window.attributes

        if (isFullScreen) {
            // Полноэкранный режим
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
            // Убираем флаг не фокусируемости для взаимодействия в полноэкранном режиме
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            // Возврат к обычному режиму с центрированием в верхней половине
            params.width = dpToPx(WINDOW_WIDTH_DP)
            params.height = dpToPx(WINDOW_HEIGHT_DP)
            params.x = 0
            params.y = dpToPx(TOP_OFFSET_DP)
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            // Восстанавливаем Gravity для корректного позиционирования
            window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        }

        window.attributes = params
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onPause() {
        super.onPause()
        if (!isFullScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFullScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }
}
