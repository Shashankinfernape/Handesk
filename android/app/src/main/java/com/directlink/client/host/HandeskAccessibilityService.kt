package com.directlink.client.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class HandeskAccessibilityService : AccessibilityService() {

    companion object {
        var instance: HandeskAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null
    }

    private var currentGesturePath: Path? = null
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var isDragging: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: we only use accessibility for gesture dispatching
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    fun injectTouchDown(x: Float, y: Float) {
        lastX = x
        lastY = y
        isDragging = false
        currentGesturePath = Path().apply {
            moveTo(x, y)
        }
    }

    fun injectTouchMove(x: Float, y: Float) {
        if (currentGesturePath == null) {
            injectTouchDown(x, y)
            return
        }
        val dx = Math.abs(x - lastX)
        val dy = Math.abs(y - lastY)
        if (dx > 2f || dy > 2f) {
            isDragging = true
            val path = Path().apply {
                moveTo(lastX, lastY)
                lineTo(x, y)
            }
            lastX = x
            lastY = y
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val builder = GestureDescription.Builder().addStroke(stroke)
            dispatchGesture(builder.build(), null, null)
        }
    }

    fun injectTouchUp(x: Float, y: Float) {
        if (!isDragging) {
            // It's a clean tap at (x, y)
            injectTap(x, y)
        }
        currentGesturePath = null
        isDragging = false
    }

    fun injectTap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 40)
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), null, null)
    }

    fun injectScroll(startX: Float, startY: Float, deltaY: Float) {
        val endY = startY + deltaY
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), null, null)
    }

    fun injectBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun injectHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun injectRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun injectNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
}
