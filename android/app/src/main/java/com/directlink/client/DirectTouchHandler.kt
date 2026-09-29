package com.directlink.client

import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper
import android.util.Log

class DirectTouchHandler(private val networkClient: NetworkClient, private val view: View) : View.OnTouchListener {
    
    var videoAspectRatio = 16f / 9f
    
    private var isHoldDragging = false
    private var lastScrollY = 0f

    // Local Pan and Zoom state
    private var scale = 1f
    private var translateX = 0f
    private var translateY = 0f

    // Custom Long Press Implementation
    private val handler = Handler(Looper.getMainLooper())
    private var startX = 0f
    private var startY = 0f
    private var isLongPressCanceled = false
    private val longPressRunnable = Runnable {
        if (!isLongPressCanceled) {
            isHoldDragging = true
            networkClient.sendMouseButton(1, true)
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

    private var isScrollConfirmed = false
    private var startScrollY = 0f

    private var lastFocusScreenX = 0f
    private var lastFocusScreenY = 0f

    private val scaleGestureDetector = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // detector.focusX is in unscaled coordinates because Android inverse-transforms the MotionEvent.
            // We must convert it back to physical screen coordinates for accurate pan/zoom math.
            lastFocusScreenX = (detector.focusX * scale) + translateX
            lastFocusScreenY = (detector.focusY * scale) + translateY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (isScrollConfirmed) return false

            val scaleFactor = detector.scaleFactor
            val currentFocusScreenX = (detector.focusX * scale) + translateX
            val currentFocusScreenY = (detector.focusY * scale) + translateY

            val newScale = (scale * scaleFactor).coerceIn(1f, 10f)
            val scaleChange = newScale / scale

            // Zoom translation offset (keeps the zoom centered precisely on the fingers)
            translateX = currentFocusScreenX - (currentFocusScreenX - translateX) * scaleChange
            translateY = currentFocusScreenY - (currentFocusScreenY - translateY) * scaleChange
            
            // Pan translation offset (moves the view if the fingers drag across the screen)
            translateX += (currentFocusScreenX - lastFocusScreenX)
            translateY += (currentFocusScreenY - lastFocusScreenY)

            lastFocusScreenX = currentFocusScreenX
            lastFocusScreenY = currentFocusScreenY
            scale = newScale

            applyTransform()
            return true
        }
    })

    private fun applyTransform() {
        // Constrain the view so it doesn't fly off the screen
        val maxTransX = 0f
        val minTransX = view.width - (view.width * scale)
        val maxTransY = 0f
        val minTransY = view.height - (view.height * scale)

        if (scale == 1f) {
            translateX = 0f
            translateY = 0f
        } else {
            translateX = translateX.coerceIn(minTransX, maxTransX)
            translateY = translateY.coerceIn(minTransY, maxTransY)
        }

        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = scale
        view.scaleY = scale
        view.translationX = translateX
        view.translationY = translateY
    }

    private var threeFingerStartY = 0f
    private var threeFingerTriggered = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleGestureDetector.onTouchEvent(event)
        
        // Android framework automatically inverse-transforms event.x and event.y when view is scaled/translated.
        // So event.x and event.y are always in the precise, unscaled original coordinate space of the SurfaceView.
        
        // Calculate exact video bounds inside SurfaceView to remove hardware black bars
        val viewAspectRatio = v.width.toFloat() / v.height.toFloat()
        var videoWidth = v.width.toFloat()
        var videoHeight = v.height.toFloat()
        var padX = 0f
        var padY = 0f
        
        if (videoAspectRatio > viewAspectRatio) {
            videoHeight = v.width / videoAspectRatio
            padY = (v.height - videoHeight) / 2f
        } else {
            videoWidth = v.height * videoAspectRatio
            padX = (v.width - videoWidth) / 2f
        }

        val adjustedX = event.x - padX
        val adjustedY = event.y - padY
        
        val normX = ((adjustedX / videoWidth) * 65535).toInt().coerceIn(0, 65535)
        val normY = ((adjustedY / videoHeight) * 65535).toInt().coerceIn(0, 65535)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                networkClient.sendMouseMove(normX, normY)
                startX = event.x
                startY = event.y
                isLongPressCanceled = false
                threeFingerTriggered = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, 700)
            }
            
            MotionEvent.ACTION_POINTER_DOWN -> {
                isLongPressCanceled = true
                handler.removeCallbacks(longPressRunnable)
                
                if (event.pointerCount == 2) {
                    startScrollY = event.getY(1)
                    lastScrollY = event.getY(1)
                    isScrollConfirmed = false
                } else if (event.pointerCount == 3) {
                    threeFingerStartY = event.y
                    threeFingerTriggered = false
                }
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    if (!isLongPressCanceled && (Math.abs(event.x - startX) > 15f || Math.abs(event.y - startY) > 15f)) {
                        isLongPressCanceled = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    
                    networkClient.sendMouseMove(normX, normY)
                } else if (event.pointerCount == 2) {
                    if (!scaleGestureDetector.isInProgress) {
                        val currentY = event.getY(1)
                        if (!isScrollConfirmed) {
                            if (Math.abs(currentY - startScrollY) > 10f) {
                                isScrollConfirmed = true
                                lastScrollY = currentY
                            }
                        } else {
                            val deltaY = currentY - lastScrollY
                            if (Math.abs(deltaY) > 2f) {
                                val scrollAmount = (deltaY * 0.8f).toInt()
                                networkClient.sendMouseScroll(scrollAmount)
                                lastScrollY = currentY
                            }
                        }
                    }
                } else if (event.pointerCount == 3) {
                    if (!threeFingerTriggered) {
                        val deltaY = event.y - threeFingerStartY
                        if (deltaY < -150f) {
                            threeFingerTriggered = true
                            networkClient.sendKeyEvent(0x5B, true)
                            networkClient.sendKeyEvent(0x09, true)
                            networkClient.sendKeyEvent(0x09, false)
                            networkClient.sendKeyEvent(0x5B, false)
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
