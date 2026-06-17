package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Locale

class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // LERP interpolation constants for premium micro-animations
    private val COORDINATE_LERP_FACTOR = 0.25f
    private val ALPHA_LERP_FACTOR = 0.20f

    // Painting objects
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00FF88") // Premium light neon/mint green
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E676") // Deeper neon green for distinct corner accents
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1500FF88") // Subtle translucent green fill (approx 8% opacity)
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC00E676") // Sleek high-contrast rounded badge background (80% opacity)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    // Coordinates mapping and interpolation states
    private val targetRect = RectF()
    private val currentRect = RectF()
    private var targetAlpha = 0f
    private var currentAlpha = 0f

    private var targetLabel: String? = null
    private var targetDistance: Float = -1f

    /**
     * Updates the bounding box parameters from the analyzer.
     * Expects normalized coordinates (0.0 to 1.0) on the raw camera frame.
     */
    fun setDetection(
        label: String?,
        distance: Float,
        rawLeft: Float,
        rawTop: Float,
        rawRight: Float,
        rawBottom: Float,
        rotationDegrees: Int,
        frameWidth: Int,
        frameHeight: Int
    ) {
        if (label == null) {
            targetAlpha = 0f
            postInvalidate()
            return
        }

        this.targetLabel = label
        this.targetDistance = distance
        this.targetAlpha = 1f

        // 1. Determine portrait source dimensions after rotation (CameraX frame layout mapping)
        val srcWidth = if (rotationDegrees == 90 || rotationDegrees == 270) frameHeight else frameWidth
        val srcHeight = if (rotationDegrees == 90 || rotationDegrees == 270) frameWidth else frameHeight

        // 2. Map raw coordinates (from sensor landscape frame) into portrait screen-aligned space
        val rotLeft: Float
        val rotTop: Float
        val rotRight: Float
        val rotBottom: Float

        when (rotationDegrees) {
            90 -> {
                // sensor x is along raw frameWidth, sensor y is along raw frameHeight
                // Top-right corner of sensor maps to top-left of screen
                rotLeft = 1f - rawBottom
                rotRight = 1f - rawTop
                rotTop = rawLeft
                rotBottom = rawRight
            }
            270 -> {
                rotLeft = rawTop
                rotRight = rawBottom
                rotTop = 1f - rawRight
                rotBottom = 1f - rawLeft
            }
            180 -> {
                rotLeft = 1f - rawRight
                rotRight = 1f - rawLeft
                rotTop = 1f - rawBottom
                rotBottom = 1f - rawTop
            }
            else -> { // 0 or 360
                rotLeft = rawLeft
                rotRight = rawRight
                rotTop = rawTop
                rotBottom = rawBottom
            }
        }

        // 3. Perform aspect-ratio correction for PreviewView's FILL_CENTER scale mode
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth > 0f && viewHeight > 0f && srcWidth > 0 && srcHeight > 0) {
            val scaleX = viewWidth / srcWidth
            val scaleY = viewHeight / srcHeight
            val scale = Math.max(scaleX, scaleY)

            val previewWidth = srcWidth * scale
            val previewHeight = srcHeight * scale

            val offsetX = (viewWidth - previewWidth) / 2f
            val offsetY = (viewHeight - previewHeight) / 2f

            // 4. Set final target bounds in screen pixel coordinate space (clamped with safety margins to prevent cropping)
            val leftMargin = 24f
            val rightMargin = 24f
            val topMargin = 96f
            val bottomMargin = 24f

            val clampedLeft = (rotLeft * previewWidth + offsetX).coerceIn(leftMargin, viewWidth - rightMargin)
            val clampedRight = (rotRight * previewWidth + offsetX).coerceIn(leftMargin, viewWidth - rightMargin)
            val clampedTop = (rotTop * previewHeight + offsetY).coerceIn(topMargin, viewHeight - bottomMargin)
            val clampedBottom = (rotBottom * previewHeight + offsetY).coerceIn(topMargin, viewHeight - bottomMargin)

            targetRect.left = Math.min(clampedLeft, clampedRight)
            targetRect.right = Math.max(clampedLeft, clampedRight)
            targetRect.top = Math.min(clampedTop, clampedBottom)
            targetRect.bottom = Math.max(clampedTop, clampedBottom)

            postInvalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Interpolate visibility alpha
        currentAlpha += (targetAlpha - currentAlpha) * ALPHA_LERP_FACTOR
        if (currentAlpha < 0.01f) {
            currentAlpha = 0f
            currentRect.setEmpty()
            return
        }

        // Interpolate coordinates towards target when visible
        if (targetAlpha > 0f) {
            if (currentRect.isEmpty) {
                currentRect.set(targetRect)
            } else {
                currentRect.left += (targetRect.left - currentRect.left) * COORDINATE_LERP_FACTOR
                currentRect.top += (targetRect.top - currentRect.top) * COORDINATE_LERP_FACTOR
                currentRect.right += (targetRect.right - currentRect.right) * COORDINATE_LERP_FACTOR
                currentRect.bottom += (targetRect.bottom - currentRect.bottom) * COORDINATE_LERP_FACTOR
            }
        }

        // Update paint alphas to match current animation state
        val alphaInt = (currentAlpha * 255).toInt()
        boxPaint.alpha = alphaInt
        cornerPaint.alpha = alphaInt
        fillPaint.alpha = (currentAlpha * 21).toInt() // subtle fill (8% max)
        textBgPaint.alpha = (currentAlpha * 204).toInt() // badge background (80% max)
        textPaint.alpha = alphaInt

        // Draw bounding box components
        drawBoundingBoxHUD(canvas)

        // Run next animation frame if any values are still interpolating
        val alphaDiff = Math.abs(targetAlpha - currentAlpha)
        val rectDiff = if (targetAlpha > 0f) {
            Math.abs(targetRect.left - currentRect.left) + Math.abs(targetRect.top - currentRect.top)
        } else 0f

        if (alphaDiff > 0.01f || rectDiff > 1f) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawBoundingBoxHUD(canvas: Canvas) {
        // 1. Draw solid rounded rectangle fill and border
        canvas.drawRoundRect(currentRect, 16f, 16f, fillPaint)
        canvas.drawRoundRect(currentRect, 16f, 16f, boxPaint)

        // 2. Draw thick tech corner brackets
        val rectWidth = currentRect.width()
        val rectHeight = currentRect.height()
        val bracketLen = Math.min(rectWidth, rectHeight) * 0.25f // length of corners is 25% of smallest dimension

        // Top-Left Corner
        canvas.drawLine(currentRect.left, currentRect.top, currentRect.left + bracketLen, currentRect.top, cornerPaint)
        canvas.drawLine(currentRect.left, currentRect.top, currentRect.left, currentRect.top + bracketLen, cornerPaint)

        // Top-Right Corner
        canvas.drawLine(currentRect.right, currentRect.top, currentRect.right - bracketLen, currentRect.top, cornerPaint)
        canvas.drawLine(currentRect.right, currentRect.top, currentRect.right, currentRect.top + bracketLen, cornerPaint)

        // Bottom-Left Corner
        canvas.drawLine(currentRect.left, currentRect.bottom, currentRect.left + bracketLen, currentRect.bottom, cornerPaint)
        canvas.drawLine(currentRect.left, currentRect.bottom, currentRect.left, currentRect.bottom - bracketLen, cornerPaint)

        // Bottom-Right Corner
        canvas.drawLine(currentRect.right, currentRect.bottom, currentRect.right - bracketLen, currentRect.bottom, cornerPaint)
        canvas.drawLine(currentRect.right, currentRect.bottom, currentRect.right, currentRect.bottom - bracketLen, cornerPaint)

        // 3. Draw object badge/tag (with smart alignment to prevent screen edge cropping)
        val label = targetLabel ?: return
        val text = if (targetDistance > 0.1f) {
            "${label.uppercase(Locale.US)} • ${String.format(Locale.US, "%.1fm", targetDistance)}"
        } else {
            label.uppercase(Locale.US)
        }

        val textWidth = textPaint.measureText(text)
        val textHeight = 34f
        val paddingX = 24f
        val paddingY = 16f
        val badgeWidth = textWidth + paddingX * 2f
        val badgeHeight = textHeight + paddingY * 2f

        val margin = 24f
        val topMargin = 96f
        val viewWidth = width.toFloat()

        // 3a. Position badge horizontally (clamp to view finder width with side margins)
        var badgeLeft = currentRect.left
        if (badgeLeft + badgeWidth > viewWidth - margin) {
            badgeLeft = viewWidth - margin - badgeWidth
        }
        if (badgeLeft < margin) {
            badgeLeft = margin
        }

        // 3b. Position badge vertically (prefer drawing above the box, fallback to inside box if near top)
        var badgeTop = currentRect.top - badgeHeight
        if (badgeTop < topMargin) {
            badgeTop = currentRect.top + paddingY
        }

        val tagRect = RectF(
            badgeLeft,
            badgeTop,
            badgeLeft + badgeWidth,
            badgeTop + badgeHeight
        )

        canvas.drawRoundRect(tagRect, 8f, 8f, textBgPaint)
        canvas.drawText(text, badgeLeft + paddingX, badgeTop + paddingY + textHeight - 2f, textPaint)
    }
}
