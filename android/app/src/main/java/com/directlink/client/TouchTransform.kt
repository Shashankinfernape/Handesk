package com.directlink.client

import kotlin.math.max
import kotlin.math.min

/**
 * Encapsulates the bidirectional coordinate transformation between the physical Android
 * screen/touch space and the host remote desktop normalized space (0..65535).
 * 
 * Works cleanly under any aspect ratio, letterboxing, pillarboxing, zoom scale, and pan offset.
 */
class TouchTransform {
    // Container size (the full visible remote session viewport)
    var containerWidth: Float = 1f
    var containerHeight: Float = 1f

    // Content size before zoom (the letterboxed/pillarboxed video surface)
    var contentWidth: Float = 1f
    var contentHeight: Float = 1f
    var contentLeft: Float = 0f
    var contentTop: Float = 0f

    // Viewport transform
    var scale: Float = 1f
    var offsetX: Float = 0f
    var offsetY: Float = 0f

    /**
     * Updates the layout geometry of the container and the letterboxed content.
     */
    fun updateLayout(cWidth: Float, cHeight: Float, desktopAspect: Float) {
        containerWidth = max(cWidth, 1f)
        containerHeight = max(cHeight, 1f)

        val cAspect = containerWidth / containerHeight
        val targetAspect = if (desktopAspect > 0.1f) desktopAspect else 16f / 9f

        if (cAspect > targetAspect) {
            // Container is wider -> pillarbox (black bars on left/right)
            contentHeight = containerHeight
            contentWidth = contentHeight * targetAspect
            contentLeft = (containerWidth - contentWidth) / 2f
            contentTop = 0f
        } else {
            // Container is taller -> letterbox (black bars on top/bottom)
            contentWidth = containerWidth
            contentHeight = contentWidth / targetAspect
            contentLeft = 0f
            contentTop = (containerHeight - contentHeight) / 2f
        }
    }

    /**
     * Updates the zoom scale and pan offset.
     */
    fun updateTransform(newScale: Float, newOffsetX: Float, newOffsetY: Float) {
        scale = max(newScale, 0.85f)
        offsetX = newOffsetX
        offsetY = newOffsetY
    }

    /**
     * Converts a raw physical touch screen X coordinate into remote normalized X (0..65535).
     */
    fun screenToNormX(screenX: Float): Int {
        val totalLeft = contentLeft + offsetX
        val totalScaledW = contentWidth * scale
        if (totalScaledW <= 0f) return 32767
        val contentX = (screenX - totalLeft) / totalScaledW
        return (contentX * 65535f).toInt().coerceIn(0, 65535)
    }

    /**
     * Converts a raw physical touch screen Y coordinate into remote normalized Y (0..65535).
     */
    fun screenToNormY(screenY: Float): Int {
        val totalTop = contentTop + offsetY
        val totalScaledH = contentHeight * scale
        if (totalScaledH <= 0f) return 32767
        val contentY = (screenY - totalTop) / totalScaledH
        return (contentY * 65535f).toInt().coerceIn(0, 65535)
    }

    /**
     * Converts a normalized desktop X (0..1f or 0..65535) to physical screen X coordinate.
     */
    fun normToScreenX(normX: Float): Float {
        val fraction = if (normX > 1f) normX / 65535f else normX
        return contentLeft + offsetX + (fraction * contentWidth * scale)
    }

    /**
     * Converts a normalized desktop Y (0..1f or 0..65535) to physical screen Y coordinate.
     */
    fun normToScreenY(normY: Float): Float {
        val fraction = if (normY > 1f) normY / 65535f else normY
        return contentTop + offsetY + (fraction * contentHeight * scale)
    }

    /**
     * Checks if the zoom scale is within snapping range of the default 1.0x fit.
     * When fingers release within this threshold, the view magnetically snaps back to default.
     */
    fun isNearDefault(testScale: Float): Boolean {
        return testScale < 1.15f
    }

    /**
     * Clamps a raw pan offset while allowing generous corner overscroll (blank background).
     * Allows any corner of the remote desktop to be panned into the center of the mobile screen.
     * When scale <= 1.01f, offsets lock strictly to (0, 0).
     */
    fun clampOffsets(rawScale: Float, rawOffsetX: Float, rawOffsetY: Float): Pair<Float, Float> {
        val s = max(rawScale, 0.85f)
        val scaledW = contentWidth * s
        val scaledH = contentHeight * s

        // Corner overscroll leeway: allows dragging any corner to the center of the screen
        // so the user can easily interact with taskbars, close buttons, etc., with blank bg margin.
        val overscrollX = containerWidth * 0.55f
        val overscrollY = containerHeight * 0.55f

        val minX = overscrollX - contentLeft - scaledW
        val maxX = (containerWidth - overscrollX) - contentLeft

        val minY = overscrollY - contentTop - scaledH
        val maxY = (containerHeight - overscrollY) - contentTop

        val clampedX = rawOffsetX.coerceIn(min(minX, maxX), max(minX, maxX))
        val clampedY = rawOffsetY.coerceIn(min(minY, maxY), max(minY, maxY))
        return Pair(clampedX, clampedY)
    }
}
