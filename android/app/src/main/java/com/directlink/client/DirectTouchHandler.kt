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
 * DirectTouchHandler
 *
 * GESTURE MAP:
 *   1 finger tap          → left click (no mouse hover trail)
 *   1 finger double-tap   → right click
 *   1 finger long press   → left button drag (moves mouse while dragging)
 *
 *   2 fingers scroll      → mouse wheel (normal speed)
 *   2 fingers swipe up    → keyboard (fast flick up)
 *   2 fingers swipe down  → toolbar (fast flick down)
 *
 *   3 fingers PINCH       → visual zoom in/out + pan
 */
class DirectTouchHandler(
    private val networkClient: NetworkClient,
    private val textureView: TextureView,
    private val overlayView: View
) : View.OnTouchListener {

    var videoAspectRatio = 16f / 9f
    var onTwoFingerSwipeUp: (() -> Unit)? = null
    var onTwoFingerSwipeDown: (() -> Unit)? = null

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
        val cx = (sx - panX) / scale
        val cy = (sy - panY) / scale
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
        if (!isLongPressCanceled) { 
            isHoldDragging = true
            val (nx, ny) = screenToNorm(startX, startY)
            networkClient.sendMouseMove(nx, ny)
            networkClient.sendMouseButton(1, true) 
        }
    }

    // ── Tap / double-tap ─────────────────────────────────────────────────────
    private val gestureDetector = GestureDetector(overlayView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (e.pointerCount > 1) return false
                val (nx, ny) = screenToNorm(e.x, e.y)
                networkClient.sendMouseMove(nx, ny)
                networkClient.sendMouseButton(1, true)
                networkClient.sendMouseButton(1, false)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val (nx, ny) = screenToNorm(e.x, e.y)
                networkClient.sendMouseMove(nx, ny)
                networkClient.sendMouseButton(2, true)
                networkClient.sendMouseButton(2, false)
                return true
            }
        })

    // ── 2-finger scroll / swipe ──────────────────────────────────────────────
    private var scrollStartY = 0f
    private var scrollStartTime = 0L
    private var scrollLastY  = 0f
    private var scrollActive = false
    private var scrollIntegral = 0f
    private var twoFingerSwipeTriggered = false

    // ── 3-finger zoom + pan ───────────────────────────────────────────────────
    private var threeFingerActive = false
    private var lastCentroidX = 0f;  private var lastCentroidY = 0f
    private var lastRadius    = 0f

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
        if (event.pointerCount == 1) gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                isLongPressCanceled = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, 700)
                
                // Note: INTENTIONALLY NOT sending mouse move here.
                // It ensures a pure "touch" interface without hover trails.
                
                scrollActive = false; threeFingerActive = false
                twoFingerSwipeTriggered = false
                scrollIntegral = 0f
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isLongPressCanceled = true; handler.removeCallbacks(longPressRunnable)
                if (isHoldDragging) { networkClient.sendMouseButton(1, false); isHoldDragging = false }

                when (event.pointerCount) {
                    2 -> {
                        scrollStartY = (event.getY(0) + event.getY(1)) / 2f
                        scrollLastY = scrollStartY
                        scrollStartTime = System.currentTimeMillis()
                        scrollActive = false; scrollIntegral = 0f
                        twoFingerSwipeTriggered = false
                    }
                    3 -> {
                        scrollActive = false
                        val (cx, cy) = centroid(event)
                        lastCentroidX = cx; lastCentroidY = cy
                        lastRadius    = avgRadius(event, cx, cy)
                        threeFingerActive = true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (event.pointerCount) {
                    1 -> {
                        if (!isLongPressCanceled &&
                            (abs(event.x - startX) > 15f || abs(event.y - startY) > 15f)) {
                            isLongPressCanceled = true; handler.removeCallbacks(longPressRunnable)
                        }
                        // Only send mouse move if we are actually dragging a window/icon
                        if (isHoldDragging) {
                            val (nx, ny) = screenToNorm(event.x, event.y)
                            networkClient.sendMouseMove(nx, ny)
                        }
                    }

                    2 -> {
                        if (!threeFingerActive) {
                            if (twoFingerSwipeTriggered) return@onTouch true
                            
                            val cy = (event.getY(0) + event.getY(1)) / 2f
                            val dy = cy - scrollStartY
                            val dt = System.currentTimeMillis() - scrollStartTime
                            
                            if (!scrollActive) {
                                // Smooth swipe detection: > 60px within 350ms is a clear UI flick
                                if (dt < 350 && dy < -60f) {
                                    twoFingerSwipeTriggered = true
                                    onTwoFingerSwipeUp?.invoke()
                                } else if (dt < 350 && dy > 60f) {
                                    twoFingerSwipeTriggered = true
                                    onTwoFingerSwipeDown?.invoke()
                                } else if (abs(dy) > 20f && dt >= 150) {
                                    // Slower/sustained movement -> lock into scroll mode
                                    scrollActive = true
                                    scrollLastY = cy
                                }
                            } else {
                                val scrollDy = cy - scrollLastY
                                scrollIntegral += scrollDy / 3f
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
                        val radiusRatio = if (lastRadius > 0f) radius / lastRadius else 1f

                        val factor = radiusRatio.coerceIn(0.85f, 1.18f)
                        val newScale = (scale * factor).coerceIn(1f, 8f)
                        val ratio = newScale / scale
                        panX = cx - (cx - panX) * ratio
                        panY = cy - (cy - panY) * ratio
                        panX += cx - lastCentroidX
                        panY += cy - lastCentroidY
                        scale = newScale
                        applyTransform()

                        lastCentroidX = cx; lastCentroidY = cy; lastRadius = radius
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                when (event.pointerCount) {
                    3 -> {
                        threeFingerActive = false
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
                twoFingerSwipeTriggered = false
                scrollIntegral = 0f
            }
        }
        return true
    }
}
