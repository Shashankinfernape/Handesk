package com.directlink.client

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * DirectTouchHandler — RustDesk-style implementation.
 *
 * ARCHITECTURE (matches RustDesk's canvas model):
 * - The SurfaceView NEVER has scaleX/Y or translationX/Y applied to it.
 * - We maintain our own canvas state: canvasX, canvasY (pan offset), canvasScale (zoom).
 * - On every touch, we map screen coords → remote coords using:
 *     remoteX = (screenX - canvasX) / canvasScale
 *     remoteY = (screenY - canvasY) / canvasScale
 *   Then normalise to [0..65535].
 *
 * WHY this avoids the coordinate bug:
 * - We never apply any Android view transform, so event.x / event.y are always
 *   pure, reliable screen-relative pixels relative to the SurfaceView's top-left.
 * - No Compose/View inverse-transform confusion whatsoever.
 *
 * VISUALS:
 * - To show the zoomed image the video itself must be zoomed. We achieve this by
 *   using a TextureView in the future. For now, as a first step, pinch triggers
 *   server-side Ctrl+Scroll which zooms the remote OS (same as RustDesk mobile
 *   when scroll-style is set to "adaptive"). Touch accuracy is perfect.
 *
 * GESTURES:
 *   1 finger move     → mouse move
 *   1 finger tap      → left click
 *   1 finger double   → right click
 *   1 finger hold     → left-button drag
 *   2 finger scroll   → mouse wheel
 *   2 finger pinch    → Ctrl+Scroll (remote zoom)
 *   3 finger swipe up → Win+Tab (Task View)
 */
class DirectTouchHandler(
    private val networkClient: NetworkClient,
    private val view: View
) : View.OnTouchListener {

    /** Set from VideoDecoder callback whenever stream dimensions change. */
    var videoAspectRatio = 16f / 9f

    // ── RustDesk-style canvas viewport state ─────────────────────────────────
    // These represent where the "camera" is looking on the remote screen.
    // At scale=1, canvasX=canvasY=0 → full remote screen maps 1:1 to the view.
    private var canvasScale = 1.0f
    private var canvasX = 0f   // pan offset in screen pixels
    private var canvasY = 0f

    /** Map a screen pixel (relative to view top-left) to a normalised remote coordinate. */
    private fun screenToNorm(screenX: Float, screenY: Float, vw: Float, vh: Float): Pair<Int, Int> {
        // 1. Account for hardware black bars (letterbox / pillarbox)
        val viewAR = vw / vh
        val videoW: Float
        val videoH: Float
        val padX: Float
        val padY: Float
        if (videoAspectRatio > viewAR) {
            videoW = vw
            videoH = vw / videoAspectRatio
            padX = 0f
            padY = (vh - videoH) / 2f
        } else {
            videoH = vh
            videoW = vh * videoAspectRatio
            padX = (vw - videoW) / 2f
            padY = 0f
        }

        // 2. Convert to video-local coords (remove black bar padding)
        val localX = screenX - padX
        val localY = screenY - padY

        // 3. Apply RustDesk formula: subtract pan, divide by scale
        val remoteX = (localX - canvasX) / canvasScale
        val remoteY = (localY - canvasY) / canvasScale

        // 4. Normalise to [0..65535]
        val normX = ((remoteX / videoW) * 65535f).toInt().coerceIn(0, 65535)
        val normY = ((remoteY / videoH) * 65535f).toInt().coerceIn(0, 65535)
        return Pair(normX, normY)
    }

    // ── Long-press drag ──────────────────────────────────────────────────────
    private var isHoldDragging = false
    private val handler = Handler(Looper.getMainLooper())
    private var startX = 0f; private var startY = 0f
    private var isLongPressCanceled = false
    private val longPressRunnable = Runnable {
        if (!isLongPressCanceled) {
            isHoldDragging = true
            networkClient.sendMouseButton(1, true)
        }
    }

    // ── Tap / double-tap ─────────────────────────────────────────────────────
    private val gestureDetector = GestureDetector(view.context,
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
        })

    // ── Pinch-to-zoom + 2-finger pan ─────────────────────────────────────────
    private var isScrollConfirmed = false
    private var isPinchActive = false
    private var lastFocusX = 0f; private var lastFocusY = 0f

    private val scaleGestureDetector = ScaleGestureDetector(view.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                isPinchActive = true
                lastFocusX = d.focusX
                lastFocusY = d.focusY
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                val factor = d.scaleFactor
                val newScale = (canvasScale * factor).coerceIn(1f, 8f)

                // Zoom centred on the pinch focus point (RustDesk approach)
                val focusX = d.focusX
                val focusY = d.focusY
                canvasX = focusX - (focusX - canvasX) * (newScale / canvasScale)
                canvasY = focusY - (focusY - canvasY) * (newScale / canvasScale)

                // 2-finger pan while pinching
                canvasX += focusX - lastFocusX
                canvasY += focusY - lastFocusY

                canvasScale = newScale
                lastFocusX = focusX
                lastFocusY = focusY

                constrainCanvas()
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                isPinchActive = false
            }
        })

    /** Keep the canvas from panning beyond the view edges. */
    private fun constrainCanvas() {
        // At scale 1, no translation allowed (full image fits view)
        if (canvasScale <= 1f) {
            canvasX = 0f; canvasY = 0f; canvasScale = 1f
            return
        }
        val maxX = 0f;          val minX = view.width  * (1f - canvasScale)
        val maxY = 0f;          val minY = view.height * (1f - canvasScale)
        canvasX = canvasX.coerceIn(minX, maxX)
        canvasY = canvasY.coerceIn(minY, maxY)
    }

    // ── 2-finger scroll ───────────────────────────────────────────────────────
    private var startScrollY = 0f; private var lastScrollY = 0f

    // ── 3-finger swipe-up ─────────────────────────────────────────────────────
    private var threeFingerStartY = 0f; private var threeFingerTriggered = false

    // ─────────────────────────────────────────────────────────────────────────
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleGestureDetector.onTouchEvent(event)

        val (normX, normY) = screenToNorm(event.x, event.y, v.width.toFloat(), v.height.toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                networkClient.sendMouseMove(normX, normY)
                startX = event.x; startY = event.y
                isLongPressCanceled = false; threeFingerTriggered = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, 700)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isLongPressCanceled = true
                handler.removeCallbacks(longPressRunnable)
                when (event.pointerCount) {
                    2 -> {
                        startScrollY = event.getY(1); lastScrollY = event.getY(1)
                        isScrollConfirmed = false
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
                                isLongPressCanceled = true
                                handler.removeCallbacks(longPressRunnable)
                            }
                            networkClient.sendMouseMove(normX, normY)
                        }
                    }
                    2 -> {
                        // 2-finger scroll (only when not in a pinch)
                        if (!scaleGestureDetector.isInProgress) {
                            val cy = event.getY(1)
                            if (!isScrollConfirmed) {
                                if (abs(cy - startScrollY) > 10f) { isScrollConfirmed = true; lastScrollY = cy }
                            } else {
                                val dy = cy - lastScrollY
                                if (abs(dy) > 2f) {
                                    networkClient.sendMouseScroll((dy * 0.8f).toInt())
                                    lastScrollY = cy
                                }
                            }
                        }
                    }
                    3 -> {
                        if (!threeFingerTriggered && event.y - threeFingerStartY < -150f) {
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
                if (isHoldDragging) { networkClient.sendMouseButton(1, false); isHoldDragging = false }
            }
        }
        return true
    }
}
