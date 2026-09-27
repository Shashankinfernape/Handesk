package com.directlink.client

import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper

class DirectTouchHandler(private val networkClient: NativeClient, private val view: View) : View.OnTouchListener {
    
    private var isHoldDragging = false
    private var lastScrollY = 0f

    // Custom Long Press Implementation
    private val handler = Handler(Looper.getMainLooper())
    private var startX = 0f
    private var startY = 0f
    private var isLongPressCanceled = false
    private val longPressRunnable = Runnable {
        if (!isLongPressCanceled) {
            isHoldDragging = true
            networkClient.sendMouseButton(1, true) // Left click down to start drag
        }
    }

    private val gestureDetector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            networkClient.sendMouseButton(1, true)
            networkClient.sendMouseButton(1, false)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            networkClient.sendMouseButton(2, true)
            networkClient.sendMouseButton(2, false)
            return true
        }
    })

    private var isPinchConfirmed = false
    private var isScrollConfirmed = false
    private var startScrollY = 0f

    private val scaleGestureDetector = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var accumulatedScale = 1.0f

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            accumulatedScale = 1.0f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (isScrollConfirmed) return false // Lock out pinch if we are already scrolling

            accumulatedScale *= detector.scaleFactor
            
            if (!isPinchConfirmed) {
                // Harder initial activation threshold (20% expansion or 15% contraction)
                if (accumulatedScale > 1.20f || accumulatedScale < 0.85f) {
                    isPinchConfirmed = true
                    networkClient.sendKeyEvent(17, true) // Hold CTRL down
                    
                    // Trigger the first zoom tick based on direction
                    if (accumulatedScale > 1.0f) {
                        networkClient.sendMouseScroll(120)
                    } else {
                        networkClient.sendMouseScroll(-120)
                    }
                    accumulatedScale = 1.0f // Reset for continuous ticks
                }
            } else {
                // Already in Pinch Mode. Reduce sensitivity of continuous zooming (15% per tick).
                if (accumulatedScale > 1.15f) {
                    networkClient.sendMouseScroll(120)
                    accumulatedScale = 1.0f
                } else if (accumulatedScale < 0.85f) {
                    networkClient.sendMouseScroll(-120)
                    accumulatedScale = 1.0f
                }
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (isPinchConfirmed) {
                networkClient.sendKeyEvent(17, false) // Release CTRL
                isPinchConfirmed = false
            }
        }
    })

    private var lastMoveTime = 0L

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        // Feed events to standard GestureDetector
        gestureDetector.onTouchEvent(event)
        
        // Feed events to ScaleGestureDetector (Pinch-to-Zoom)
        scaleGestureDetector.onTouchEvent(event)
        
        val normX = ((event.x / v.width) * 65535).toInt().coerceIn(0, 65535)
        val normY = ((event.y / v.height) * 65535).toInt().coerceIn(0, 65535)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                networkClient.sendMouseMove(normX, normY)
                lastMoveTime = System.currentTimeMillis()
                
                // Start custom long press timer (1 full second)
                startX = event.x
                startY = event.y
                isLongPressCanceled = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, 400)
            }
            
            MotionEvent.ACTION_POINTER_DOWN -> {
                isLongPressCanceled = true
                handler.removeCallbacks(longPressRunnable)
                
                if (event.pointerCount == 2) {
                    startScrollY = event.getY(1)
                    lastScrollY = event.getY(1)
                    isScrollConfirmed = false
                    isPinchConfirmed = false
                }
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    // Cancel long press if they move their finger more than 45 pixels before the 400ms is up
                    if (!isLongPressCanceled && (Math.abs(event.x - startX) > 45f || Math.abs(event.y - startY) > 45f)) {
                        isLongPressCanceled = true
                        handler.removeCallbacks(longPressRunnable)
                    }

                    // Instantly transmit raw touch coordinates at maximum digitizer polling rate (120Hz/240Hz)
                    networkClient.sendMouseMove(normX, normY)
                } else if (event.pointerCount == 2) {
                    // Gesture Isolation State Machine
                    val currentY = event.getY(1)
                    
                    if (!isPinchConfirmed) {
                        if (!isScrollConfirmed) {
                            // Check if they moved enough (15px) to explicitly confirm a scroll
                            if (Math.abs(currentY - startScrollY) > 15f) {
                                isScrollConfirmed = true
                                lastScrollY = currentY
                            }
                        } else {
                            // Scroll is confirmed, execute smooth scrolling
                            val deltaY = currentY - lastScrollY
                            if (Math.abs(deltaY) > 1f) {
                                // Natural scrolling: Swipe UP (negative delta) -> Wheel Down (negative) -> Page scrolls DOWN
                                val scrollAmount = (deltaY * 1.5f).toInt()
                                networkClient.sendMouseScroll(scrollAmount)
                                lastScrollY = currentY
                            }
                        }
                    }
                }
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isLongPressCanceled = true
                handler.removeCallbacks(longPressRunnable)

                if (isHoldDragging) {
                    networkClient.sendMouseButton(1, false)
                    isHoldDragging = false
                }
            }
        }
        return true
    }
}
