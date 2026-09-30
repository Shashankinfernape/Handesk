package com.directlink.client

import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.hypot

/**
 * DirectTouchHandler — Clean gesture separation.
 *
 * GESTURE MAP (mutually exclusive, no accidental cross-triggers):
 *   1 finger tap          → left click
 *   1 finger double-tap   → right click
 *   1 finger long press   → left button drag
 *   1 finger move         → mouse move / drag
 *
 *   2 fingers scroll      → mouse wheel (scroll only, NEVER zoom)
 *   2 fingers tap         → right click
 *
 *   3 fingers PINCH       → visual zoom in/out + pan (ONLY way to zoom)
 *   3 fingers swipe-UP    → Win+Tab  (only if no pinch detected in gesture)
 *
 * WHY 3-finger zoom:
 *   Separating scroll (2-finger) from zoom (3-finger) eliminates ALL accidental
 *   zoom triggers while scrolling. User intentionally places 3 fingers to zoom.
 */
class DirectTouchHandler(
    private val networkClient: NetworkClient,
    private val textureView: TextureView,
    private val overlayView: View
) : View.OnTouchListener {

    var videoAspectRatio = 16f / 9f

    // ── Canvas viewport (RustDesk pattern) ───────────────────────────────────
    private var scale = 1f
    private var panX  = 0f
    private var panY  = 0f

    private fun applyTransform() {
        if (scale <= 1.001f) {
            scale = 1f; panX = 0f; panY = 0f
            textureView.post { textureView.setTransform(null) }
            return
        }
        val tw = textureView.width.toFloat()
        val th = textureView.height.toFloat()
        panX = panX.coerceIn(tw * (1f - scale), 0f)
        panY = panY.coerceIn(th * (1f - scale), 0f)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(panX, panY)
        textureView.post { textureView.setTransform(m) }
    }

    private fun screenToNorm(sx: Float, sy: Float): Pair<Int, Int> {
        val tw = textureView.width.toFloat().takeIf { it > 0 } ?: 1f
        val th = textureView.height.toFloat().takeIf { it > 0 } ?: 1f
        // 1. Invert visual transform
        val cx = (sx - panX) / scale
        val cy = (sy - panY) / scale
        // 2. Black-bar correction
        val viewAR = tw / th
        val vw: Float; val vh: Float; val px: Float; val py: Float
        if (videoAspectRatio > viewAR) {
            vw = tw; vh = tw / videoAspectRatio; px = 0f; py = (th - vh) / 2f
        } else {
            vh = th; vw = th * videoAspectRatio; px = (tw - vw) / 2f; py = 0f
        }
        val nx = (((cx - px) / vw) * 65535f).toInt().coerceIn(0, 65535)
        val ny = (((cy - py) / vh) * 65535f).toInt().coerceIn(0, 65535)
        return Pair(nx, ny)
    }

    // ── Long-press drag ───────────────────────────────────────────────────────
    private var isHoldDragging = false
    private val handler = Handler(Looper.getMainLooper())
    private var startX = 0f; private var startY = 0f
    private var isLongPressCanceled = false
    private val longPressRunnable = Runnable {
        if (!isLongPressCanceled) { isHoldDragging = true; networkClient.sendMouseButton(1, true) }
    }

    // ── Tap / double-tap ─────────────────────────────────────────────────────
    private val gestureDetector = GestureDetector(overlayView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (e.pointerCount > 1) return false
                networkClient.sendMouseButton(1, true); networkClient.sendMouseButton(1, false)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                networkClient.sendMouseButton(2, true); networkClient.sendMouseButton(2, false)
                return true
            }
        })

    // ── 2-finger scroll (ONLY, no zoom) ──────────────────────────────────────
    private var scrollStartY = 0f
    private var scrollLastY  = 0f
    private var scrollActive = false
    private var scrollIntegral = 0f

    // ── 3-finger zoom + pan ───────────────────────────────────────────────────
    // We track 3-finger gestures manually to distinguish:
    //   a) Pinch (spread / close) → zoom
    //   b) Swipe-up (all fingers move upward) → Win+Tab
    private var threeFingerActive   = false
    private var threeFingerPinching = false    // locked once pinch detected
    private var threeFingerSwiped   = false    // locked once swipe detected

    // Last centroid and average radius for 3-finger math
    private var lastCentroidX = 0f;  private var lastCentroidY = 0f
    private var lastRadius    = 0f
    private var startCentroidY = 0f  // used for swipe detection

    private fun centroid(event: MotionEvent): Pair<Float, Float> {
        var x = 0f; var y = 0f
        for (i in 0 until event.pointerCount) { x += event.getX(i); y += event.getY(i) }
        return Pair(x / event.pointerCount, y / event.pointerCount)
    }

    private fun avgRadius(event: MotionEvent, cx: Float, cy: Float): Float {
        var r = 0f
        for (i in 0 until event.pointerCount) {
            r += hypot(event.getX(i) - cx, event.getY(i) - cy)
        }
        return r / event.pointerCount
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        // Feed 1-finger gestures to standard detector (tap / double-tap)
        if (event.pointerCount == 1) gestureDetector.onTouchEvent(event)

        val (normX, normY) = screenToNorm(event.x, event.y)

        when (event.actionMasked) {

            // ── FINGER DOWN ──────────────────────────────────────────────────
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                isLongPressCanceled = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, 700)
                networkClient.sendMouseMove(normX, normY)
                // Reset all multi-finger state
                scrollActive = false; threeFingerActive = false
                threeFingerPinching = false; threeFingerSwiped = false
                scrollIntegral = 0f
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isLongPressCanceled = true; handler.removeCallbacks(longPressRunnable)
                if (isHoldDragging) { networkClient.sendMouseButton(1, false); isHoldDragging = false }

                when (event.pointerCount) {
                    2 -> {
                        // 2-finger: prepare scroll
                        scrollStartY = (event.getY(0) + event.getY(1)) / 2f
                        scrollLastY  = scrollStartY
                        scrollActive = false; scrollIntegral = 0f
                    }
                    3 -> {
                        // 3-finger: prepare zoom/swipe — cancel any 2-finger scroll
                        scrollActive = false
                        val (cx, cy) = centroid(event)
                        lastCentroidX = cx; lastCentroidY = cy
                        lastRadius    = avgRadius(event, cx, cy)
                        startCentroidY = cy
                        threeFingerActive = true; threeFingerPinching = false; threeFingerSwiped = false
                    }
                }
            }

            // ── FINGER MOVE ───────────────────────────────────────────────────
            MotionEvent.ACTION_MOVE -> {
                when (event.pointerCount) {
                    1 -> {
                        if (!isLongPressCanceled &&
                            (abs(event.x - startX) > 15f || abs(event.y - startY) > 15f)) {
                            isLongPressCanceled = true; handler.removeCallbacks(longPressRunnable)
                        }
                        networkClient.sendMouseMove(normX, normY)
                    }

                    2 -> {
                        if (!threeFingerActive) {
                            val cy = (event.getY(0) + event.getY(1)) / 2f
                            if (!scrollActive) {
                                if (abs(cy - scrollStartY) > 8f) {
                                    scrollActive = true; scrollLastY = cy
                                }
                            } else {
                                val dy = cy - scrollLastY
                                // Integrator: accumulate, fire on ±threshold
                                scrollIntegral += dy / 3f
                                when {
                                    scrollIntegral >  1f -> { networkClient.sendMouseScroll(( scrollIntegral * 25f).toInt()); scrollIntegral = 0f }
                                    scrollIntegral < -1f -> { networkClient.sendMouseScroll((scrollIntegral * 25f).toInt()); scrollIntegral = 0f }
                                }
                                scrollLastY = cy
                            }
                        }
                    }

                    3 -> {
                        if (!threeFingerActive) return@onTouch true
                        val (cx, cy) = centroid(event)
                        val radius   = avgRadius(event, cx, cy)

                        val radiusRatio  = if (lastRadius > 0f) radius / lastRadius else 1f
                        val centroidDy   = cy - lastCentroidY
                        val totalSwipeY  = cy - startCentroidY

                        // --- Distinguish: swipe-up vs pinch ---
                        // Rule: if centroid moves > 80px upward AND radius hasn't changed > 12%, it's a swipe
                        // Rule: if radius changes > 5% (spread/close), it's a pinch
                        if (!threeFingerSwiped && !threeFingerPinching) {
                            if (abs(radiusRatio - 1f) > 0.05f) {
                                threeFingerPinching = true    // lock into zoom mode
                            } else if (totalSwipeY < -80f) {
                                threeFingerSwiped = true      // lock into swipe mode
                                networkClient.sendKeyEvent(0x5B, true)
                                networkClient.sendKeyEvent(0x09, true)
                                networkClient.sendKeyEvent(0x09, false)
                                networkClient.sendKeyEvent(0x5B, false)
                            }
                        }

                        if (threeFingerPinching) {
                            // Zoom centered on the 3-finger centroid (RustDesk formula)
                            val factor = radiusRatio.coerceIn(0.85f, 1.18f)
                            val newScale = (scale * factor).coerceIn(1f, 8f)
                            val ratio = newScale / scale
                            panX = cx - (cx - panX) * ratio
                            panY = cy - (cy - panY) * ratio
                            // Pan with centroid movement
                            panX += cx - lastCentroidX
                            panY += cy - lastCentroidY
                            scale = newScale
                            applyTransform()
                        }

                        lastCentroidX = cx; lastCentroidY = cy; lastRadius = radius
                    }
                }
            }

            // ── FINGER UP ─────────────────────────────────────────────────────
            MotionEvent.ACTION_POINTER_UP -> {
                when (event.pointerCount) {
                    3 -> {
                        // One 3-finger left → back to 2-finger scroll state
                        threeFingerActive = false; threeFingerPinching = false
                        scrollActive = false; scrollIntegral = 0f
                    }
                    2 -> {
                        scrollActive = false; scrollIntegral = 0f
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isLongPressCanceled = true; handler.removeCallbacks(longPressRunnable)
                if (isHoldDragging) { networkClient.sendMouseButton(1, false); isHoldDragging = false }
                scrollActive = false; threeFingerActive = false
                threeFingerPinching = false; threeFingerSwiped = false
                scrollIntegral = 0f
            }
        }
        return true
    }
}
