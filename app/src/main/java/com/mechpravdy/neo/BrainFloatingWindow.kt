package com.mechpravdy.neo

import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.ReactInstanceManager
import com.facebook.react.common.LifecycleState
import com.facebook.react.shell.MainReactPackage
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class BrainFloatingWindow : ReactActivity() {

    companion object {
        private const val WINDOW_WIDTH_DP = 250
        private const val WINDOW_HEIGHT_DP = 350
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
                val initialProps = Bundle()
                return initialProps
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupFloatingWindow()
        setupDragging()
        setupFullScreenToggle()
    }

    private fun setupFloatingWindow() {
        window.apply {
            setFormat(PixelFormat.TRANSLUCENT)
            setGravity(Gravity.TOP or Gravity.START)

            val params = attributes
            params.width = dpToPx(WINDOW_WIDTH_DP)
            params.height = dpToPx(WINDOW_HEIGHT_DP)
            params.x = 20
            params.y = 100

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                params.flags = params.flags or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            } else {
                params.flags = params.flags or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }

            attributes = params
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun setupDragging() {
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        var startRawX = 0f
        var startRawY = 0f

        rootView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    startRawX = event.rawX
                    startRawY = event.rawY
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
                        params.x = (params.x + deltaX).coerceAtLeast(0)
                        params.y = (params.y + deltaY).coerceAtLeast(0)
                        window.attributes = params
                        startRawX = event.rawX
                        startRawY = event.rawY
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
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.width = dpToPx(WINDOW_WIDTH_DP)
            params.height = dpToPx(WINDOW_HEIGHT_DP)
            params.x = 20
            params.y = 100
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        window.attributes = params
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onPause() {
        super.onPause()
        if (!isFullScreen) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFullScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }
}
