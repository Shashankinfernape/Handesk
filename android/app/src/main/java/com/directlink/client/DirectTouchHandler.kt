package com.directlink.client

import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper

/**
 * DirectTouchHandler — definitive, correct implementation.
 *
 * Key design decision: we DO NOT apply any scaleX/scaleY/translationX/translationY
 * to the SurfaceView. Doing so breaks touch dispatch inside Compose's AndroidView
 * because Compose intercepts touches before the Android View system can inverse-transform
 * the coordinates, so event.x/event.y remain in raw screen space while the math expects
 * transformed space — causing every tap to land in the wrong place.
 *
 * Zoom behaviour: pinch-to-zoom sends Ctrl+ScrollWheel to the PC, zooming the REMOTE
 * content. The SurfaceView always fills the screen and touch coordinates are a simple
 * linear mapping with no local transform state.
 *
 * Black-bar correction: the SurfaceView fills the entire screen (fillMaxSize), but
 * MediaCodec may letterbox/pillarbox the video inside it. We correct for that using
 * the real video aspect ratio reported by the decoder.
 */
class DirectTouchHandler(
    private val networkClient: NetworkClient,
    private val view: View
) : View.OnTouchListener {

    /** Set this whenever the decoder reports the true video dimensions. */
    var videoAspectRatio = 16f / 9f

    // ── Long-press / drag ────────────────────────────────────────────────────
    private var isHoldDragging = false
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

    // ── Tap / double-tap ─────────────────────────────────────────────────────
    private val gestureDetector = GestureDetector(
        view.context,
        object : GestureDetector.SimpleOnGestureListener() {
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
        }
    )

    // ── Pinch-to-zoom (Ctrl+Scroll on the remote PC) ─────────────────────────
    private var isPinchConfirmed = false
    private var isScrollConfirmed = false
    private var accumulatedPinchScale = 1f

    private val scaleGestureDetector = ScaleGestureDetector(
        view.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                accumulatedPinchScale = 1f
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isScrollConfirmed) return false
                accumulatedPinchScale *= detector.scaleFactor
                if (!isPinchConfirmed) {
                    if (accumulatedPinchScale > 1.20f || accumulatedPinchScale < 0.83f) {
                        isPinchConfirmed = true
                        networkClient.sendKeyEvent(0x11, true) // CTRL down
                        val tick = if (accumulatedPinchScale > 1f) 120 else -120
                        networkClient.sendMouseScroll(tick)
                        accumulatedPinchScale = 1f
                    }
                } else {
                    if (accumulatedPinchScale > 1.15f) {
                        networkClient.sendMouseScroll(120)
                        accumulatedPinchScale = 1f
                    } else if (accumulatedPinchScale < 0.87f) {
                        networkClient.sendMouseScroll(-120)
                        accumulatedPinchScale = 1f
                    }
                }
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (isPinchConfirmed) {
                    networkClient.sendKeyEvent(0x11, false) // CTRL up
                    isPinchConfirmed = false
                }
            }
        }
    )

    // ── Two-finger scroll ────────────────────────────────────────────────────
    private var startScrollY = 0f
    private var lastScrollY = 0f

    // ── Three-finger swipe-up → Win+Tab ─────────────────────────────────────
    private var threeFingerStartY = 0f
    private var threeFingerTriggered = false

    // ─────────────────────────────────────────────────────────────────────────
    // Core touch handler
    // ─────────────────────────────────────────────────────────────────────────
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleGestureDetector.onTouchEvent(event)

        // Map event.x / event.y → normalised remote coordinates [0..65535].
        // The SurfaceView fills the full screen (fillMaxSize), but MediaCodec may
        // add letterbox/pillarbox bars. We correct for that here.
        val normX: Int
        val normY: Int
        run {
            val vw = v.width.toFloat()
            val vh = v.height.toFloat()
            val viewAR = vw / vh

            val videoW: Float
            val videoH: Float
            val padX: Float
            val padY: Float

            if (videoAspectRatio > viewAR) {
                // pillarbox (bars on top/bottom)
                videoW = vw
                videoH = vw / videoAspectRatio
                padX = 0f
                padY = (vh - videoH) / 2f
            } else {
                // letterbox (bars on left/right)
                videoH = vh
                videoW = vh * videoAspectRatio
                padX = (vw - videoW) / 2f
                padY = 0f
            }

            normX = (((event.x - padX) / videoW) * 65535f).toInt().coerceIn(0, 65535)
            normY = (((event.y - padY) / videoH) * 65535f).toInt().coerceIn(0, 65535)
        }

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
                when (event.pointerCount) {
                    2 -> {
                        startScrollY = event.getY(1)
                        lastScrollY = event.getY(1)
                        isScrollConfirmed = false
                        isPinchConfirmed = false
                    }
                    3 -> {
                        threeFingerStartY = event.y
                        threeFingerTriggered = false
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (event.pointerCount) {
                    1 -> {
                        if (!isLongPressCanceled &&
                            (Math.abs(event.x - startX) > 15f || Math.abs(event.y - startY) > 15f)
                        ) {
                            isLongPressCanceled = true
                            handler.removeCallbacks(longPressRunnable)
                        }
                        networkClient.sendMouseMove(normX, normY)
                    }
                    2 -> {
                        if (!scaleGestureDetector.isInProgress) {
                            val cy = event.getY(1)
                            if (!isScrollConfirmed) {
                                if (Math.abs(cy - startScrollY) > 10f) {
                                    isScrollConfirmed = true
                                    lastScrollY = cy
                                }
                            } else {
                                val dy = cy - lastScrollY
                                if (Math.abs(dy) > 2f) {
                                    networkClient.sendMouseScroll((dy * 0.8f).toInt())
                                    lastScrollY = cy
                                }
                            }
                        }
                    }
                    3 -> {
                        if (!threeFingerTriggered && event.y - threeFingerStartY < -150f) {
                            threeFingerTriggered = true
                            networkClient.sendKeyEvent(0x5B, true)  // Win
                            networkClient.sendKeyEvent(0x09, true)  // Tab
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
