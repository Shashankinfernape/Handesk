package com.directlink.client

import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * DirectTouchHandler — TextureView + RustDesk-style implementation.
 *
 * ARCHITECTURE:
 * - TextureView (not SurfaceView) allows setTransform(Matrix) for REAL VISUAL zoom/pan.
 * - This touch handler is set on a FULL-SCREEN transparent overlay view, NOT on the
 *   TextureView itself. This ensures touches are captured everywhere on screen, including
 *   in the zoomed-out areas when the image is panned.
 * - Coordinate mapping uses the INVERSE of the visual transform matrix (exact RustDesk pattern).
 *
 * VISUAL ZOOM FORMULA (applied to TextureView):
 *   Matrix m; m.setScale(scale, scale); m.postTranslate(panX, panY)
 *   → Content pixel at (cx, cy) appears at screen pixel (cx*scale + panX, cy*scale + panY)
 *
 * TOUCH INVERSE FORMULA (touch → remote):
 *   contentX = (screenX - panX) / scale    (RustDesk: x -= canvas.x; x /= canvas.scale)
 *   contentY = (screenY - panY) / scale
 *   Then subtract black-bar padding and normalize to [0..65535]
 *
 * GESTURES:
 *   1 finger tap          → left click
 *   1 finger double-tap   → right click
 *   1 finger long-hold    → left button drag
 *   1 finger move         → mouse move
 *   2 finger pinch        → visual zoom in/out (centered on fingers)
 *   2 finger pan          → pan the zoomed view (move camera)
 *   2 finger scroll       → mouse wheel (when NOT pinching)
 *   3 finger swipe-up     → Win+Tab (Task View)
 */
class DirectTouchHandler(
    private val networkClient: NetworkClient,
    private val textureView: TextureView,
    private val overlayView: View   // The full-screen transparent view that captures touches
) : View.OnTouchListener {

    /** Updated from VideoDecoder callback with the real decoded video dimensions. */
    var videoAspectRatio = 16f / 9f

    // ── RustDesk-style canvas viewport state ─────────────────────────────────
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f

    /** Apply the current zoom/pan as a Matrix transform on the TextureView. */
    private fun applyTransform() {
        if (scale <= 1.001f) {
            scale = 1f; panX = 0f; panY = 0f
            textureView.post { textureView.setTransform(null) }
            return
        }
        // Clamp pan so the image doesn't float off-screen
        val tw = textureView.width.toFloat()
        val th = textureView.height.toFloat()
        panX = panX.coerceIn(tw * (1f - scale), 0f)
        panY = panY.coerceIn(th * (1f - scale), 0f)

        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(panX, panY)
        textureView.post { textureView.setTransform(m) }
    }

    /** Map a screen pixel (relative to overlay top-left) → normalised remote coord [0..65535].
     *  Applies inverse-transform then black-bar correction. Mirrors RustDesk input_model.dart. */
    private fun screenToNorm(sx: Float, sy: Float): Pair<Int, Int> {
        val tw = textureView.width.toFloat()
        val th = textureView.height.toFloat()

        // 1. Invert the visual zoom/pan transform (RustDesk: x -= canvas.x; x /= canvas.scale)
        val contentX = (sx - panX) / scale
        val contentY = (sy - panY) / scale

        // 2. Black-bar correction (video may be letterboxed/pillarboxed inside the TextureView)
        val viewAR = if (th > 0f) tw / th else 16f / 9f
        val videoW: Float; val videoH: Float; val padX: Float; val padY: Float
        if (videoAspectRatio > viewAR) {
            videoW = tw; videoH = tw / videoAspectRatio
            padX = 0f;  padY = (th - videoH) / 2f
        } else {
            videoH = th; videoW = th * videoAspectRatio
            padX = (tw - videoW) / 2f; padY = 0f
        }

        val videoLocalX = contentX - padX
        val videoLocalY = contentY - padY

        // 3. Normalise to [0..65535] and clamp (RustDesk: tryGetNearestRange with 5px snap)
        val normX = ((videoLocalX / videoW) * 65535f).toInt().coerceIn(0, 65535)
        val normY = ((videoLocalY / videoH) * 65535f).toInt().coerceIn(0, 65535)
        return Pair(normX, normY)
    }

    // ── Long-press drag ──────────────────────────────────────────────────────
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
                networkClient.sendMouseButton(1, true); networkClient.sendMouseButton(1, false)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                networkClient.sendMouseButton(2, true); networkClient.sendMouseButton(2, false)
                return true
            }
        })

    // ── Pinch zoom + 2-finger pan ─────────────────────────────────────────────
    private var isPinchActive = false
    private var isScrollConfirmed = false
    private var lastFocusX = 0f; private var lastFocusY = 0f
    // Accumulated absolute scale factor (RustDesk pattern: _scale field, ratio = d.scale / _scale)
    private var pinchStartScale = 1f

    private val scaleGestureDetector = ScaleGestureDetector(overlayView.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                isPinchActive = true
                pinchStartScale = scale   // capture canvas scale at gesture start
                lastFocusX = d.focusX; lastFocusY = d.focusY
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                val focalX = d.focusX; val focalY = d.focusY
                val factor = d.scaleFactor.coerceIn(0.8f, 1.25f) // clamp per-frame change

                val newScale = (scale * factor).coerceIn(1f, 8f)
                val ratio = newScale / scale

                // RustDesk formula: _x = focalPoint.dx - (focalPoint.dx - _x) / s * _scale
                panX = focalX - (focalX - panX) * ratio
                panY = focalY - (focalY - panY) * ratio

                // 2-finger pan delta (RustDesk: panX/panY(focalPointDelta))
                panX += focalX - lastFocusX
                panY += focalY - lastFocusY

                scale = newScale
                lastFocusX = focalX; lastFocusY = focalY

                applyTransform()
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) { isPinchActive = false }
        })

    // ── 2-finger scroll (mouse wheel) ─────────────────────────────────────────
    private var startScrollY = 0f; private var lastScrollY = 0f
    // Integrator (RustDesk: _mouseScrollIntegral += delta / 4; send when crosses ±1)
    private var scrollIntegral = 0f

    // ── 3-finger swipe-up → Win+Tab ─────────────────────────────────────────
    private var threeFingerStartY = 0f; private var threeFingerTriggered = false

    // ─────────────────────────────────────────────────────────────────────────
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleGestureDetector.onTouchEvent(event)

        val (normX, normY) = screenToNorm(event.x, event.y)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                networkClient.sendMouseMove(normX, normY)
                startX = event.x; startY = event.y
                isLongPressCanceled = false; threeFingerTriggered = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, 700)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isLongPressCanceled = true; handler.removeCallbacks(longPressRunnable)
                when (event.pointerCount) {
                    2 -> {
                        startScrollY = event.getY(1); lastScrollY = event.getY(1)
                        isScrollConfirmed = false; scrollIntegral = 0f
                    }
                    3 -> { threeFingerStartY = event.y; threeFingerTriggered = false }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (event.pointerCount) {
                    1 -> {
                        if (!isPinchActive) {
                            if (!isLongPressCanceled &&
                                (abs(event.x - startX) > 15f || abs(event.y - startY) > 15f)) {
                                isLongPressCanceled = true; handler.removeCallbacks(longPressRunnable)
                            }
                            networkClient.sendMouseMove(normX, normY)
                        }
                    }
                    2 -> {
                        // 2-finger scroll when NOT in a pinch (RustDesk: threeFingerVerticalDrag / scroll)
                        if (!scaleGestureDetector.isInProgress) {
                            val cy = event.getY(1)
                            if (!isScrollConfirmed) {
                                if (abs(cy - startScrollY) > 10f) { isScrollConfirmed = true; lastScrollY = cy }
                            } else {
                                val dy = cy - lastScrollY
                                // RustDesk integrator: accumulate dy/4, fire when crosses ±1
                                scrollIntegral += dy / 4f
                                if (scrollIntegral > 1f) {
                                    networkClient.sendMouseScroll((scrollIntegral * 30f).toInt()); scrollIntegral = 0f
                                } else if (scrollIntegral < -1f) {
                                    networkClient.sendMouseScroll((scrollIntegral * 30f).toInt()); scrollIntegral = 0f
                                }
                                lastScrollY = cy
                            }
                        }
                    }
                    3 -> {
                        if (!threeFingerTriggered && event.y - threeFingerStartY < -120f) {
                            threeFingerTriggered = true
                            networkClient.sendKeyEvent(0x5B, true)
                            networkClient.sendKeyEvent(0x09, true); networkClient.sendKeyEvent(0x09, false)
                            networkClient.sendKeyEvent(0x5B, false)
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isLongPressCanceled = true; handler.removeCallbacks(longPressRunnable)
                if (isHoldDragging) { networkClient.sendMouseButton(1, false); isHoldDragging = false }
                isPinchActive = false
            }
        }
        return true
    }
}
