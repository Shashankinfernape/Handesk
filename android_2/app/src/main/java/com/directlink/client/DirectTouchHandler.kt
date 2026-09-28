package com.directlink.client

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class DirectTouchHandler(
    private val networkClient: NativeClient,
    private val view: View
) : View.OnTouchListener {

    var onSetKeyboard: ((Boolean) -> Unit)? = null

    private var isHoldDragging = false
    private var isDoubleTapPending = false
    private var doubleTapStartX = 0f
    private var doubleTapStartY = 0f
    private val touchSlop = 20f 

    private val handler = Handler(Looper.getMainLooper())
    private val holdDragRunnable = Runnable {
        if (isDoubleTapPending) {
            // User held the second tap without moving for 200ms. Lock in the drag!
            isDoubleTapPending = false
            isHoldDragging = true
            networkClient.sendMouseButton(1, true)
        }
    }

    private fun getNormX(x: Float): Int {
        return ((x / view.width) * 65535).toInt().coerceIn(0, 65535)
    }

    private fun getNormY(y: Float): Int {
        return ((y / view.height) * 65535).toInt().coerceIn(0, 65535)
    }

    private val gestureDetector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        
        // We MUST use onSingleTapConfirmed! 
        // If we let the first tap send a click, the computer will receive it instantly. Then if you 
        // try to hold the second tap to drag a file, the computer will see "Click, then Click-Hold", 
        // which forces Windows to open the file instead of dragging it!
        // By using Confirmed, we wait to see what your second tap does first.
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            networkClient.sendMouseMove(getNormX(e.x), getNormY(e.y))
            networkClient.sendMouseButton(1, true)
            networkClient.sendMouseButton(1, false)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!isDoubleTapPending && !isHoldDragging) {
                networkClient.sendMouseMove(getNormX(e.x), getNormY(e.y))
                networkClient.sendMouseButton(2, true)
                networkClient.sendMouseButton(2, false)
            }
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                // The second tap has landed! We DON'T send a click yet. 
                // We wait to see if it's a Tap (release) or a Hold (drag).
                isDoubleTapPending = true
                doubleTapStartX = e.x
                doubleTapStartY = e.y
                networkClient.sendMouseMove(getNormX(e.x), getNormY(e.y))
                handler.postDelayed(holdDragRunnable, 200) // Wait 200ms to see if it's a hold
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, 
            e2: MotionEvent, 
            distanceX: Float, 
            distanceY: Float
        ): Boolean {
            // 1-FINGER SCROLL (Normal Swipe)
            if (e2.pointerCount == 1 && !isHoldDragging && !isDoubleTapPending) {
                networkClient.sendMouseMove(getNormX(e2.x), getNormY(e2.y))

                if (abs(distanceY) > abs(distanceX)) {
                    val delta = (-distanceY * 1.5f).toInt()
                    if (delta != 0) networkClient.sendMouseScroll(delta)
                } else {
                    val delta = (-distanceX * 1.5f).toInt()
                    if (delta != 0) networkClient.sendMouseHScroll(delta)
                }
                return true
            }
            return false
        }
    })

    // Custom 2-Finger State Machine 
    private var twoFingerState = 0 
    private var startFingerDist = 0f
    private var startCenterX = 0f
    private var startCenterY = 0f

    private fun getDistance(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun getCenterX(e: MotionEvent): Float {
        if (e.pointerCount < 2) return e.x
        return (e.getX(0) + e.getX(1)) / 2f
    }

    private fun getCenterY(e: MotionEvent): Float {
        if (e.pointerCount < 2) return e.y
        return (e.getY(0) + e.getY(1)) / 2f
    }

    private var threeFingerStartY = 0f
    private var hasTriggeredThreeFinger = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        // 1-Finger intelligent drag detection
        if (event.pointerCount == 1 && event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (isDoubleTapPending) {
                val dx = event.x - doubleTapStartX
                val dy = event.y - doubleTapStartY
                if (sqrt(dx * dx + dy * dy) > touchSlop) {
                    // User moved their finger past the deadzone before the 200ms timer!
                    // It's officially a Drag/Hold. Lock it in.
                    handler.removeCallbacks(holdDragRunnable)
                    isDoubleTapPending = false
                    isHoldDragging = true
                    networkClient.sendMouseButton(1, true) // Send exactly ONE Left Click Down
                }
            }
            
            if (isHoldDragging) {
                networkClient.sendMouseMove(getNormX(event.x), getNormY(event.y))
            }
        }

        // Custom 2-Finger and 3-Finger Logic
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    twoFingerState = 0
                    startFingerDist = getDistance(event)
                    startCenterX = getCenterX(event)
                    startCenterY = getCenterY(event)
                } else if (event.pointerCount == 3) {
                    threeFingerStartY = event.y
                    hasTriggeredThreeFinger = false
                }
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2) {
                    val currentDist = getDistance(event)
                    val currentCenterX = getCenterX(event)
                    val currentCenterY = getCenterY(event)

                    if (twoFingerState == 0) {
                        val diffDist = abs(currentDist - startFingerDist)
                        val diffCenter = max(abs(currentCenterX - startCenterX), abs(currentCenterY - startCenterY))

                        if (diffDist > 40f) {
                            twoFingerState = 2
                            startFingerDist = currentDist
                            networkClient.sendKeyEvent(17, true)
                        } else if (diffCenter > 20f) {
                            twoFingerState = 1
                            networkClient.sendMouseMove(getNormX(currentCenterX), getNormY(currentCenterY))
                            networkClient.sendMouseButton(3, true)
                        }
                    } else if (twoFingerState == 1) { // Panning
                        networkClient.sendMouseMove(getNormX(currentCenterX), getNormY(currentCenterY))
                    } else if (twoFingerState == 2) { // Zooming
                        val scale = currentDist / startFingerDist
                        // Use a much smaller threshold (1.02) and smaller scroll ticks (15) 
                        // so it feels like smooth, incremental 1-by-1 percentages in browsers.
                        if (scale > 1.02f) {
                            networkClient.sendMouseScroll(15)
                            startFingerDist = currentDist
                        } else if (scale < 0.98f) {
                            networkClient.sendMouseScroll(-15)
                            startFingerDist = currentDist
                        }
                    }
                } else if (event.pointerCount == 3 && !hasTriggeredThreeFinger) {
                    val diff = event.y - threeFingerStartY
                    if (diff < -80f) {
                        onSetKeyboard?.invoke(true)
                        hasTriggeredThreeFinger = true
                    } else if (diff > 80f) {
                        onSetKeyboard?.invoke(false)
                        hasTriggeredThreeFinger = true
                    }
                }
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    if (twoFingerState == 1) {
                        networkClient.sendMouseButton(3, false)
                    } else if (twoFingerState == 2) {
                        networkClient.sendKeyEvent(17, false)
                    }
                    twoFingerState = 0
                }
                
                if (event.pointerCount <= 1 && event.actionMasked != MotionEvent.ACTION_POINTER_UP) {
                    if (isDoubleTapPending) {
                        // User lifted finger without dragging! It's a quick Double Tap!
                        handler.removeCallbacks(holdDragRunnable)
                        isDoubleTapPending = false
                        
                        // Send a flawless, instantaneous Double Click to the PC
                        networkClient.sendMouseButton(1, true)
                        networkClient.sendMouseButton(1, false)
                        
                        // Small delay to ensure Windows registers it as two separate clicks
                        handler.postDelayed({
                            networkClient.sendMouseButton(1, true)
                            networkClient.sendMouseButton(1, false)
                        }, 25)
                        
                    } else if (isHoldDragging) {
                        // User lifted finger after a drag
                        networkClient.sendMouseButton(1, false)
                        isHoldDragging = false
                    }
                }
            }
        }
        return true
    }
}
