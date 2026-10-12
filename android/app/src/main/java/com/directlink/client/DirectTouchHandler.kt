package com.directlink.client

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * DirectTouchHandler provides 3 switchable gesture interaction modes:
 * 1. HANDESK (Default): Scroll-first hybrid workflow (1-finger scroll, double-tap drag, 2-finger shortcuts)
 * 2. TOUCH: RustDesk direct touch (instant tap-to-click, long-press right-click, 1-finger direct drag)
 * 3. MOUSE: RustDesk trackpad (relative mouse movement, tap at cursor, 2-finger right-click, double-tap drag)
 */
class DirectTouchHandler(
    private val networkClient: NativeClient,
    private val view: View
) : View.OnTouchListener {

    var currentMode: GestureMode = GestureMode.HANDESK
    var onTwoFingerSingleTap: (() -> Unit)? = null
    var onTwoFingerDoubleTap: (() -> Unit)? = null

    // Callback reporting normalized cursor coordinates (0f..1f) for UI cursor overlay in MOUSE mode
    var onCursorPositionChanged: ((normX: Float, normY: Float) -> Unit)? = null

    // Callback for dynamic viewport zoom/pan requested by gestures (e.g. 2-finger pinch in Touch mode or edge pan in Mouse mode)
    var onViewportTransformUpdate: ((zoomDelta: Float, panDeltaX: Float, panDeltaY: Float, focalX: Float, focalY: Float) -> Unit)? = null

    // RustDesk-Style 3-Finger Viewport Zoom & Pan Engine Callbacks
    var onDirectViewportChange: ((newScale: Float, newOffsetX: Float, newOffsetY: Float) -> Unit)? = null
    var onRequestSnapReset: (() -> Unit)? = null

    // 3-Finger Gesture State
    private var isThreeFingerGestureActive = false
    private var threeFingerStartCentroidX = 0f
    private var threeFingerStartCentroidY = 0f
    private var threeFingerLastCentroidX = 0f
    private var threeFingerLastCentroidY = 0f
    private var threeFingerLastSpan = 0f
    private var threeFingerStartTime = 0L
    private var threeFingerMaxSpanDiff = 0f
    private var threeFingerMaxCentroidMove = 0f
    private var threeFingerLastTapTime = 0L

    // --- Virtual Cursor State (for MOUSE mode) ---
    var virtualCursorNormX = 32767f
    var virtualCursorNormY = 32767f
    var mouseSensitivity = 1.35f

    // --- Touch & Viewport Transform Engine ---
    val touchTransform = TouchTransform()

    fun updateLayout(containerW: Float, containerH: Float, desktopAspect: Float) {
        touchTransform.updateLayout(containerW, containerH, desktopAspect)
    }

    fun updateTransform(scale: Float, offsetX: Float, offsetY: Float) {
        touchTransform.updateTransform(scale, offsetX, offsetY)
    }

    private fun getNormX(x: Float): Int {
        return touchTransform.screenToNormX(x)
    }

    private fun getNormY(y: Float): Int {
        return touchTransform.screenToNormY(y)
    }

    private val handler = Handler(Looper.getMainLooper())

    // =========================================================================
    // 1. HANDESK MODE STATE & DETECTORS (Default Workflow)
    // =========================================================================
    private var isHandeskHoldDragging = false
    private var isHandeskDoubleTapPending = false
    private var handeskDoubleTapStartX = 0f
    private var handeskDoubleTapStartY = 0f
    private val handeskTouchSlop = 20f

    private val handeskHoldDragRunnable = Runnable {
        if (isHandeskDoubleTapPending) {
            isHandeskDoubleTapPending = false
            isHandeskHoldDragging = true
            networkClient.sendMouseButton(1, true)
        }
    }

    private val handeskGestureDetector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (currentMode != GestureMode.HANDESK) return false
            networkClient.sendMouseMove(getNormX(e.x), getNormY(e.y))
            networkClient.sendMouseButton(1, true)
            networkClient.sendMouseButton(1, false)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (currentMode != GestureMode.HANDESK) return
            if (!isHandeskDoubleTapPending && !isHandeskHoldDragging) {
                networkClient.sendMouseMove(getNormX(e.x), getNormY(e.y))
                networkClient.sendMouseButton(2, true)
                networkClient.sendMouseButton(2, false)
            }
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (currentMode != GestureMode.HANDESK) return false
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                isHandeskDoubleTapPending = true
                handeskDoubleTapStartX = e.x
                handeskDoubleTapStartY = e.y
                networkClient.sendMouseMove(getNormX(e.x), getNormY(e.y))
                handler.postDelayed(handeskHoldDragRunnable, 200)
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (currentMode != GestureMode.HANDESK) return false
            if (e2.pointerCount == 1 && !isHandeskHoldDragging && !isHandeskDoubleTapPending) {
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

    // Custom 2-Finger State Machine for Handesk Mode
    private var handeskTwoFingerState = 0
    private var handeskStartFingerDist = 0f
    private var handeskStartCenterX = 0f
    private var handeskStartCenterY = 0f

    // Lifecycle tracking
    private var gestureStartTime = 0L
    private var maxPointers = 0
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureMaxMove = 0f

    // 2-finger tap dispatcher (supports single & double 2-finger taps)
    private var twoFingerTapCount = 0
    private val twoFingerTapHandler = Handler(Looper.getMainLooper())
    private val twoFingerSingleTapRunnable = Runnable {
        onTwoFingerSingleTap?.invoke()
        twoFingerTapCount = 0
    }

    private fun handleTwoFingerTap() {
        twoFingerTapCount++
        if (twoFingerTapCount == 1) {
            twoFingerTapHandler.postDelayed(twoFingerSingleTapRunnable, 350)
        } else if (twoFingerTapCount == 2) {
            twoFingerTapHandler.removeCallbacks(twoFingerSingleTapRunnable)
            onTwoFingerDoubleTap?.invoke()
            twoFingerTapCount = 0
        }
    }

    // =========================================================================
    // 2. TOUCH MODE STATE (RustDesk Direct Touch Parity)
    // =========================================================================
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchIsDragging = false
    private var touchLongPressTriggered = false
    private var touchLastTapTime = 0L
    private var touchLastTapX = 0f
    private var touchLastTapY = 0f
    private var touchStartFingerDist = 0f

    private val touchLongPressRunnable = Runnable {
        if (!touchIsDragging && maxPointers == 1) {
            touchLongPressTriggered = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val nx = getNormX(touchDownX)
            val ny = getNormY(touchDownY)
            networkClient.sendMouseMove(nx, ny)
            networkClient.sendMouseButton(2, true)
            networkClient.sendMouseButton(2, false)
        }
    }

    // =========================================================================
    // 3. MOUSE MODE STATE (RustDesk Trackpad Parity)
    // =========================================================================
    private var mouseLastTouchX = 0f
    private var mouseLastTouchY = 0f
    private var mouseIsDown = false
    private var mouseDragActive = false
    private var mouseDoubleTapPending = false
    private var mouseLastTapTime = 0L
    private var mouseLongPressTriggered = false
    private var mouseTwoFingerScrollLastY = 0f

    private val mouseLongPressRunnable = Runnable {
        if (!mouseDragActive && maxPointers == 1) {
            mouseLongPressTriggered = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            networkClient.sendMouseButton(2, true)
            networkClient.sendMouseButton(2, false)
        }
    }

    private fun cancelPendingTouchActions() {
        handler.removeCallbacks(handeskHoldDragRunnable)
        handler.removeCallbacks(touchLongPressRunnable)
        handler.removeCallbacks(mouseLongPressRunnable)
        twoFingerTapHandler.removeCallbacks(twoFingerSingleTapRunnable)

        if (touchIsDragging) {
            networkClient.sendMouseButton(1, false)
            touchIsDragging = false
        }
        if (mouseDragActive) {
            networkClient.sendMouseButton(1, false)
            mouseDragActive = false
            mouseDoubleTapPending = false
        }
        if (isHandeskHoldDragging) {
            networkClient.sendMouseButton(1, false)
            isHandeskHoldDragging = false
        }
        isHandeskDoubleTapPending = false

        if (handeskTwoFingerState == 1) {
            networkClient.sendMouseButton(3, false)
            handeskTwoFingerState = 0
        } else if (handeskTwoFingerState == 2) {
            networkClient.sendKeyEvent(17, false)
            handeskTwoFingerState = 0
        }
    }

    private fun getThreeFingerCentroid(e: MotionEvent): Pair<Float, Float> {
        val count = min(e.pointerCount, 3)
        var sumX = 0f
        var sumY = 0f
        for (i in 0 until count) {
            sumX += e.getX(i)
            sumY += e.getY(i)
        }
        return Pair(sumX / count, sumY / count)
    }

    private fun getThreeFingerSpan(e: MotionEvent, cx: Float, cy: Float): Float {
        val count = min(e.pointerCount, 3)
        var sumDist = 0f
        for (i in 0 until count) {
            sumDist += hypot(e.getX(i) - cx, e.getY(i) - cy)
        }
        return if (count > 0) sumDist / count else 0f
    }

    private fun handleThreeFingerGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 3) {
                    cancelPendingTouchActions()
                    isThreeFingerGestureActive = true

                    val (cx, cy) = getThreeFingerCentroid(event)
                    val span = getThreeFingerSpan(event, cx, cy)

                    threeFingerStartCentroidX = cx
                    threeFingerStartCentroidY = cy
                    threeFingerLastCentroidX = cx
                    threeFingerLastCentroidY = cy
                    threeFingerLastSpan = span
                    threeFingerStartTime = System.currentTimeMillis()
                    threeFingerMaxSpanDiff = 0f
                    threeFingerMaxCentroidMove = 0f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 3) {
                    if (!isThreeFingerGestureActive) {
                        cancelPendingTouchActions()
                        isThreeFingerGestureActive = true

                        val (cx, cy) = getThreeFingerCentroid(event)
                        val span = getThreeFingerSpan(event, cx, cy)

                        threeFingerStartCentroidX = cx
                        threeFingerStartCentroidY = cy
                        threeFingerLastCentroidX = cx
                        threeFingerLastCentroidY = cy
                        threeFingerLastSpan = span
                        threeFingerStartTime = System.currentTimeMillis()
                        threeFingerMaxSpanDiff = 0f
                        threeFingerMaxCentroidMove = 0f
                    } else {
                        val (cx, cy) = getThreeFingerCentroid(event)
                        val span = getThreeFingerSpan(event, cx, cy)

                        val centroidDist = hypot(cx - threeFingerStartCentroidX, cy - threeFingerStartCentroidY)
                        if (centroidDist > threeFingerMaxCentroidMove) {
                            threeFingerMaxCentroidMove = centroidDist
                        }
                        val spanDiff = abs(span - threeFingerLastSpan)
                        if (spanDiff > threeFingerMaxSpanDiff) {
                            threeFingerMaxSpanDiff = spanDiff
                        }

                        val spanRatio = if (threeFingerLastSpan > 10f && span > 10f) span / threeFingerLastSpan else 1f
                        val panDeltaX = cx - threeFingerLastCentroidX
                        val panDeltaY = cy - threeFingerLastCentroidY

                        val oldScale = touchTransform.scale
                        val newScale = (oldScale * spanRatio).coerceIn(0.85f, 7.0f)
                        val actualZoom = newScale / oldScale

                        val focalRelX = cx - touchTransform.contentLeft
                        val focalRelY = cy - touchTransform.contentTop
                        val rawX = focalRelX - (focalRelX - touchTransform.offsetX) * actualZoom + panDeltaX
                        val rawY = focalRelY - (focalRelY - touchTransform.offsetY) * actualZoom + panDeltaY

                        val (clampedX, clampedY) = touchTransform.clampOffsets(newScale, rawX, rawY)
                        touchTransform.updateTransform(newScale, clampedX, clampedY)
                        onDirectViewportChange?.invoke(newScale, clampedX, clampedY)

                        threeFingerLastCentroidX = cx
                        threeFingerLastCentroidY = cy
                        threeFingerLastSpan = span
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (isThreeFingerGestureActive && event.pointerCount == 3) {
                    val duration = System.currentTimeMillis() - threeFingerStartTime
                    val isQuickTap = duration < 350 && threeFingerMaxCentroidMove < 50f && threeFingerMaxSpanDiff < 50f
                    val now = System.currentTimeMillis()

                    if (isQuickTap && (now - threeFingerLastTapTime < 350)) {
                        threeFingerLastTapTime = 0L
                        onRequestSnapReset?.invoke()
                    } else {
                        if (isQuickTap) {
                            threeFingerLastTapTime = now
                        }
                        if (touchTransform.isNearDefault(touchTransform.scale) || touchTransform.scale < 1.0f) {
                            onRequestSnapReset?.invoke()
                        } else if (touchTransform.scale > 6.0f) {
                            val clampedScale = 6.0f
                            val (cx, cy) = touchTransform.clampOffsets(clampedScale, touchTransform.offsetX, touchTransform.offsetY)
                            touchTransform.updateTransform(clampedScale, cx, cy)
                            onDirectViewportChange?.invoke(clampedScale, cx, cy)
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isThreeFingerGestureActive) {
                    val duration = System.currentTimeMillis() - threeFingerStartTime
                    val isQuickTap = duration < 350 && threeFingerMaxCentroidMove < 50f && threeFingerMaxSpanDiff < 50f
                    val now = System.currentTimeMillis()

                    if (isQuickTap && (now - threeFingerLastTapTime < 350)) {
                        threeFingerLastTapTime = 0L
                        onRequestSnapReset?.invoke()
                    } else if (touchTransform.isNearDefault(touchTransform.scale) || touchTransform.scale < 1.0f) {
                        onRequestSnapReset?.invoke()
                    }
                    isThreeFingerGestureActive = false
                }
            }
        }
        return true
    }

    // =========================================================================
    // MAIN TOUCH DISPATCHER
    // =========================================================================
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.pointerCount >= 3 || isThreeFingerGestureActive) {
            return handleThreeFingerGesture(event)
        }

        return when (currentMode) {
            GestureMode.HANDESK -> handleHandeskTouch(event)
            GestureMode.TOUCH -> handleTouchModeTouch(event)
            GestureMode.MOUSE -> handleMouseModeTouch(event)
        }
    }

    // -------------------------------------------------------------------------
    // HANDESK MODE IMPLEMENTATION (Preserves 100% of existing behavior)
    // -------------------------------------------------------------------------
    private fun handleHandeskTouch(event: MotionEvent): Boolean {
        handeskGestureDetector.onTouchEvent(event)

        if (event.pointerCount == 1 && event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (isHandeskDoubleTapPending) {
                val dx = event.x - handeskDoubleTapStartX
                val dy = event.y - handeskDoubleTapStartY
                if (sqrt(dx * dx + dy * dy) > handeskTouchSlop) {
                    handler.removeCallbacks(handeskHoldDragRunnable)
                    isHandeskDoubleTapPending = false
                    isHandeskHoldDragging = true
                    networkClient.sendMouseButton(1, true)
                }
            }
            if (isHandeskHoldDragging) {
                networkClient.sendMouseMove(getNormX(event.x), getNormY(event.y))
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartTime = System.currentTimeMillis()
                maxPointers = 1
                gestureMaxMove = 0f
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount > maxPointers) {
                    maxPointers = event.pointerCount
                }
                if (event.pointerCount == 2) {
                    handeskTwoFingerState = 0
                    handeskStartFingerDist = getDistance(event)
                    handeskStartCenterX = getCenterX(event)
                    handeskStartCenterY = getCenterY(event)
                    gestureStartX = handeskStartCenterX
                    gestureStartY = handeskStartCenterY
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2 && maxPointers == 2) {
                    val currentDist = getDistance(event)
                    val currentCenterX = getCenterX(event)
                    val currentCenterY = getCenterY(event)

                    val dist = hypot(currentCenterX - gestureStartX, currentCenterY - gestureStartY)
                    if (dist > gestureMaxMove) gestureMaxMove = dist

                    if (handeskTwoFingerState == 0) {
                        val diffDist = abs(currentDist - handeskStartFingerDist)
                        val diffCenter = max(abs(currentCenterX - handeskStartCenterX), abs(currentCenterY - handeskStartCenterY))

                        if (diffDist > 40f) {
                            handeskTwoFingerState = 2
                            handeskStartFingerDist = currentDist
                            networkClient.sendKeyEvent(17, true)
                        } else if (diffCenter > 20f) {
                            handeskTwoFingerState = 1
                            networkClient.sendMouseMove(getNormX(currentCenterX), getNormY(currentCenterY))
                            networkClient.sendMouseButton(3, true)
                        }
                    } else if (handeskTwoFingerState == 1) {
                        networkClient.sendMouseMove(getNormX(currentCenterX), getNormY(currentCenterY))
                    } else if (handeskTwoFingerState == 2) {
                        val scale = currentDist / handeskStartFingerDist
                        if (scale > 1.02f) {
                            networkClient.sendMouseScroll(15)
                            handeskStartFingerDist = currentDist
                        } else if (scale < 0.98f) {
                            networkClient.sendMouseScroll(-15)
                            handeskStartFingerDist = currentDist
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    if (handeskTwoFingerState == 1) {
                        networkClient.sendMouseButton(3, false)
                    } else if (handeskTwoFingerState == 2) {
                        networkClient.sendKeyEvent(17, false)
                    }
                    handeskTwoFingerState = 0
                }

                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    val duration = System.currentTimeMillis() - gestureStartTime
                    if (maxPointers == 2 && duration < 600 && gestureMaxMove < 200f) {
                        handleTwoFingerTap()
                    }
                }

                if (event.pointerCount <= 1 && event.actionMasked != MotionEvent.ACTION_POINTER_UP) {
                    if (isHandeskDoubleTapPending) {
                        handler.removeCallbacks(handeskHoldDragRunnable)
                        isHandeskDoubleTapPending = false
                        networkClient.sendMouseButton(1, true)
                        networkClient.sendMouseButton(1, false)
                        handler.postDelayed({
                            networkClient.sendMouseButton(1, true)
                            networkClient.sendMouseButton(1, false)
                        }, 25)
                    } else if (isHandeskHoldDragging) {
                        networkClient.sendMouseButton(1, false)
                        isHandeskHoldDragging = false
                    }
                }
            }
        }
        return true
    }

    // -------------------------------------------------------------------------
    // TOUCH MODE IMPLEMENTATION (RustDesk Direct Touch Parity)
    // -------------------------------------------------------------------------
    private fun handleTouchModeTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartTime = System.currentTimeMillis()
                maxPointers = 1
                gestureMaxMove = 0f

                touchDownX = event.x
                touchDownY = event.y
                touchIsDragging = false
                touchLongPressTriggered = false

                // Start 450ms timer for Right Click long press
                handler.postDelayed(touchLongPressRunnable, 450)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount > maxPointers) {
                    maxPointers = event.pointerCount
                }
                handler.removeCallbacks(touchLongPressRunnable)

                // If second finger arrives while dragging, release left button to avoid stuck clicks
                if (touchIsDragging) {
                    networkClient.sendMouseButton(1, false)
                    touchIsDragging = false
                }

                if (event.pointerCount == 2) {
                    gestureStartX = (event.getX(0) + event.getX(1)) / 2f
                    gestureStartY = (event.getY(0) + event.getY(1)) / 2f
                    touchStartFingerDist = getDistance(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && maxPointers == 1) {
                    val dist = hypot(event.x - touchDownX, event.y - touchDownY)
                    if (dist > 18f) {
                        handler.removeCallbacks(touchLongPressRunnable)
                        if (!touchLongPressTriggered) {
                            if (!touchIsDragging) {
                                touchIsDragging = true
                                val nx = getNormX(event.x)
                                val ny = getNormY(event.y)
                                networkClient.sendMouseMove(nx, ny)
                                networkClient.sendMouseButton(1, true) // Left DOWN (Direct Drag!)
                            } else {
                                val nx = getNormX(event.x)
                                val ny = getNormY(event.y)
                                networkClient.sendMouseMove(nx, ny)
                            }
                        }
                    }
                } else if (event.pointerCount == 2 && maxPointers == 2) {
                    val cx = (event.getX(0) + event.getX(1)) / 2f
                    val cy = (event.getY(0) + event.getY(1)) / 2f
                    val dist = hypot(cx - gestureStartX, cy - gestureStartY)
                    if (dist > gestureMaxMove) gestureMaxMove = dist

                    val currentDist = getDistance(event)
                    val zoomDelta = if (touchStartFingerDist > 10f && currentDist > 10f) currentDist / touchStartFingerDist else 1f
                    val panDeltaX = cx - gestureStartX
                    val panDeltaY = cy - gestureStartY

                    if (abs(zoomDelta - 1f) > 0.005f || hypot(panDeltaX, panDeltaY) > 2f) {
                        onViewportTransformUpdate?.invoke(zoomDelta, panDeltaX, panDeltaY, cx, cy)
                        gestureStartX = cx
                        gestureStartY = cy
                        touchStartFingerDist = currentDist
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(touchLongPressRunnable)
                val duration = System.currentTimeMillis() - gestureStartTime

                if (maxPointers == 1) {
                    if (touchIsDragging) {
                        networkClient.sendMouseButton(1, false) // Left UP
                        touchIsDragging = false
                    } else if (!touchLongPressTriggered && duration < 350) {
                        val now = System.currentTimeMillis()
                        val distFromLastTap = hypot(event.x - touchLastTapX, event.y - touchLastTapY)
                        val isDoubleTap = (now - touchLastTapTime < 300) && (distFromLastTap < 40f)

                        val nx = getNormX(event.x)
                        val ny = getNormY(event.y)
                        networkClient.sendMouseMove(nx, ny)

                        if (isDoubleTap) {
                            // Double Left Click
                            networkClient.sendMouseButton(1, true)
                            networkClient.sendMouseButton(1, false)
                            handler.postDelayed({
                                networkClient.sendMouseButton(1, true)
                                networkClient.sendMouseButton(1, false)
                            }, 20)
                            touchLastTapTime = 0L
                        } else {
                            // Single Left Click
                            networkClient.sendMouseButton(1, true)
                            networkClient.sendMouseButton(1, false)
                            touchLastTapTime = now
                            touchLastTapX = event.x
                            touchLastTapY = event.y
                        }
                    }
                } else if (maxPointers == 2 && duration < 600 && gestureMaxMove < 200f) {
                    handleTwoFingerTap()
                }
                touchIsDragging = false
                touchLongPressTriggered = false
            }
        }
        return true
    }

    // -------------------------------------------------------------------------
    // MOUSE MODE IMPLEMENTATION (RustDesk Trackpad Parity)
    // -------------------------------------------------------------------------
    private fun handleMouseModeTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartTime = System.currentTimeMillis()
                maxPointers = 1
                gestureMaxMove = 0f

                mouseLastTouchX = event.x
                mouseLastTouchY = event.y
                mouseIsDown = true
                mouseLongPressTriggered = false

                val now = System.currentTimeMillis()
                if (now - mouseLastTapTime < 300) {
                    // Potential double-tap hold-drag!
                    mouseDoubleTapPending = true
                }

                handler.postDelayed(mouseLongPressRunnable, 500)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount > maxPointers) {
                    maxPointers = event.pointerCount
                }
                handler.removeCallbacks(mouseLongPressRunnable)

                if (event.pointerCount == 2) {
                    mouseTwoFingerScrollLastY = (event.getY(0) + event.getY(1)) / 2f
                    gestureStartX = (event.getX(0) + event.getX(1)) / 2f
                    gestureStartY = (event.getY(0) + event.getY(1)) / 2f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && maxPointers == 1) {
                    val dx = event.x - mouseLastTouchX
                    val dy = event.y - mouseLastTouchY
                    val moveDist = hypot(dx, dy)

                    if (moveDist > 6f) {
                        handler.removeCallbacks(mouseLongPressRunnable)

                        if (mouseDoubleTapPending && !mouseDragActive) {
                            mouseDragActive = true
                            networkClient.sendMouseButton(1, true) // Left DOWN for drag
                        }

                        val contentW = touchTransform.contentWidth.takeIf { it > 0 } ?: 1080f
                        val contentH = touchTransform.contentHeight.takeIf { it > 0 } ?: 1920f
                        val currentScale = touchTransform.scale.coerceAtLeast(1f)

                        // Relative delta is scaled by zoom level: dividing by currentScale ensures
                        // that physical movement sensitivity feels identical across 1x to 6x zoom (RustDesk parity)!
                        val normDeltaX = (dx / (contentW * currentScale)) * 65535f * mouseSensitivity
                        val normDeltaY = (dy / (contentH * currentScale)) * 65535f * mouseSensitivity

                        virtualCursorNormX = (virtualCursorNormX + normDeltaX).coerceIn(0f, 65535f)
                        virtualCursorNormY = (virtualCursorNormY + normDeltaY).coerceIn(0f, 65535f)

                        // Transmit updated absolute cursor position to Windows host
                        networkClient.sendMouseMove(virtualCursorNormX.toInt(), virtualCursorNormY.toInt())

                        // Notify Compose overlay to render virtual mouse pointer
                        onCursorPositionChanged?.invoke(
                            virtualCursorNormX / 65535f,
                            virtualCursorNormY / 65535f
                        )

                        // Edge Auto-Panning in Mouse Mode (Camera follows cursor like RustDesk)
                        if (currentScale > 1.05f) {
                            val cursorScreenX = touchTransform.normToScreenX(virtualCursorNormX)
                            val cursorScreenY = touchTransform.normToScreenY(virtualCursorNormY)
                            val edgeMargin = 48f
                            var edgePanX = 0f
                            var edgePanY = 0f
                            if (cursorScreenX < edgeMargin) {
                                edgePanX = (edgeMargin - cursorScreenX) * 0.4f
                            } else if (cursorScreenX > touchTransform.containerWidth - edgeMargin) {
                                edgePanX = (touchTransform.containerWidth - edgeMargin - cursorScreenX) * 0.4f
                            }
                            if (cursorScreenY < edgeMargin) {
                                edgePanY = (edgeMargin - cursorScreenY) * 0.4f
                            } else if (cursorScreenY > touchTransform.containerHeight - edgeMargin) {
                                edgePanY = (touchTransform.containerHeight - edgeMargin - cursorScreenY) * 0.4f
                            }
                            if (edgePanX != 0f || edgePanY != 0f) {
                                onViewportTransformUpdate?.invoke(1f, edgePanX, edgePanY, cursorScreenX, cursorScreenY)
                            }
                        }

                        mouseLastTouchX = event.x
                        mouseLastTouchY = event.y
                    }
                } else if (event.pointerCount == 2 && maxPointers == 2) {
                    val currentCenterY = (event.getY(0) + event.getY(1)) / 2f
                    val scrollDy = currentCenterY - mouseTwoFingerScrollLastY
                    if (abs(scrollDy) > 4f) {
                        networkClient.sendMouseScroll((scrollDy * 1.5f).toInt())
                        mouseTwoFingerScrollLastY = currentCenterY
                    }

                    val cx = (event.getX(0) + event.getX(1)) / 2f
                    val cy = (event.getY(0) + event.getY(1)) / 2f
                    val dist = hypot(cx - gestureStartX, cy - gestureStartY)
                    if (dist > gestureMaxMove) gestureMaxMove = dist
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(mouseLongPressRunnable)
                val duration = System.currentTimeMillis() - gestureStartTime

                if (maxPointers == 1) {
                    if (mouseDragActive) {
                        networkClient.sendMouseButton(1, false) // Left UP
                        mouseDragActive = false
                        mouseDoubleTapPending = false
                    } else if (!mouseLongPressTriggered && duration < 300) {
                        val now = System.currentTimeMillis()
                        val isDoubleTap = (now - mouseLastTapTime < 300)

                        if (isDoubleTap) {
                            // Double Click at current cursor location
                            networkClient.sendMouseButton(1, true)
                            networkClient.sendMouseButton(1, false)
                            handler.postDelayed({
                                networkClient.sendMouseButton(1, true)
                                networkClient.sendMouseButton(1, false)
                            }, 20)
                            mouseLastTapTime = 0L
                        } else {
                            // Single Click at current cursor location
                            networkClient.sendMouseButton(1, true)
                            networkClient.sendMouseButton(1, false)
                            mouseLastTapTime = now
                        }
                    }
                } else if (maxPointers == 2 && duration < 400 && gestureMaxMove < 80f) {
                    // 2-Finger Tap in Mouse Mode = RIGHT CLICK at cursor! (RustDesk Standard)
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    networkClient.sendMouseButton(2, true)
                    networkClient.sendMouseButton(2, false)
                }

                mouseIsDown = false
                mouseDoubleTapPending = false
                mouseLongPressTriggered = false
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    mouseTwoFingerScrollLastY = 0f
                }
            }
        }
        return true
    }

    // --- Helper Math ---
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
}
