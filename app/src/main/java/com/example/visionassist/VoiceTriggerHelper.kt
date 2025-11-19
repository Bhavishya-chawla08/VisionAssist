package com.example.visionassist

import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity


class VoiceTriggerHelper(
    private val activity: AppCompatActivity,
    private val onVoiceTrigger: () -> Unit
) {
    private var volumeUpPressed = false
    private var volumeDownPressed = false
    private var swipeCount = 0
    private var lastSwipeTime = 0L

    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 != null) {
                    if (e1.y - e2.y > 200)  {
                        val currentTime = System.currentTimeMillis()

                        if (currentTime - lastSwipeTime < 800) {
                            swipeCount++
                            if (swipeCount >= 3) {
                                swipeCount = 0
                                onVoiceTrigger()
                            }
                        } else {
                            swipeCount = 1
                        }
                        lastSwipeTime = currentTime
                    }
                }
                return true
            }
        }
    )


    fun handleTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }


    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volumeUpPressed = event.action == KeyEvent.ACTION_DOWN
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volumeDownPressed = event.action == KeyEvent.ACTION_DOWN
            }
        }

        if (volumeUpPressed && volumeDownPressed) {
            onVoiceTrigger()
            volumeUpPressed = false
            volumeDownPressed = false
        }

        return false
    }
}
