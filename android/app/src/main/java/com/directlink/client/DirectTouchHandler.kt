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
                scrollLastY = event.y // track for 1-finger scroll
                
                // Note: INTENTIONALLY NOT sending mouse move here.
                // It ensures a pure "touch" interface without hover trails.
                
                scrollActive = false; threeFingerActive = false
                twoFingerSwipeTriggered = false
                scrollIntegral = 0f
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
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
                        val cy = event.y
                        val dy = cy - scrollLastY // reusing scrollLastY initialized in ACTION_DOWN
                        scrollIntegral += dy / 3f
                        when {
                            scrollIntegral >  1f -> { networkClient.sendMouseScroll(( scrollIntegral * 25f).toInt()); scrollIntegral = 0f }
                            scrollIntegral < -1f -> { networkClient.sendMouseScroll((scrollIntegral * 25f).toInt()); scrollIntegral = 0f }
                        }
                        scrollLastY = cy
                    }

                    2 -> {
                        if (!threeFingerActive) {
                            if (twoFingerSwipeTriggered) return@onTouch true
                            
                            val cy = (event.getY(0) + event.getY(1)) / 2f
                            val dy = cy - scrollStartY
                            val dt = System.currentTimeMillis() - scrollStartTime
                            
                            // Since 1-finger handles scroll, 2-finger is purely for UI swipes.
                            // We don't have to worry about accidentally scrolling.
                            if (dt < 400 && dy < -50f) {
                                twoFingerSwipeTriggered = true
                                onTwoFingerSwipeUp?.invoke()
                            } else if (dt < 400 && dy > 50f) {
                                twoFingerSwipeTriggered = true
                                onTwoFingerSwipeDown?.invoke()
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
                scrollActive = false; threeFingerActive = false
                twoFingerSwipeTriggered = false
                scrollIntegral = 0f
            }
        }
        return true
    }
}
