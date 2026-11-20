package dev.ryuya.galaxyglass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.VectorDrawable
import android.view.View
import android.widget.ImageView
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Styles the native plate without replacing drawables that SystemUI may tint or cast. */
@RequiresApi(33)
internal class GlassSystemSurfaceEffect(
    private val material: GlassMaterial,
    private val powerActionIcon: Boolean,
    private val onDrawn: () -> Unit = {},
) {
    private val outline = Outline()
    private val shape = Rect()
    private val actionCircle by lazy { RectF() }
    private val screenToLocal by lazy { Matrix() }
    private val radii = FloatArray(8)
    private val layer = Plate()
    private var background: Drawable? = null
    private var originalAlpha = 255
    private var appliedAlpha: Int? = null
    private var placement: Placement? = null
    private var nativeOutline = false

    private enum class Placement { BACKGROUND, OVERLAY }

    fun update(view: View, strength: Int, fallbackDp: Int, keepFill: Boolean = false,
        boundedPlate: Boolean = false): Boolean {
        if (strength <= 0 || view.width <= 0 || view.height <= 0 || !view.isShown) {
            restore(view)
            return false
        }
        if (placement == Placement.BACKGROUND && view.background !== layer) placement = null
        val stock = view.background?.takeUnless { it === layer }
        val density = view.resources.displayMetrics.density
        if (stock !== background) {
            restoreAlpha()
            background = stock
        }
        val resolved = if (powerActionIcon) resolvePowerActionCircle(view, density)
            else resolveShape(view, stock, density * fallbackDp)
        if (!resolved) {
            restore(view)
            return false
        }
        layer.strength = strength
        layer.dark = view.resources.configuration.isNightModeActive
        layer.setBounds(shape)
        layer.radii.indices.forEach { layer.radii[it] = radii[it] }

        if (stock == null && !powerActionIcon && !keepFill) {
            if (placement == Placement.OVERLAY) view.overlay.remove(layer)
            layer.fill = true
            if (view.background !== layer) setBackgroundPreservingPadding(view, layer)
            placement = Placement.BACKGROUND
        } else {
            layer.fill = false
            if (placement != Placement.OVERLAY) view.overlay.add(layer)
            placement = Placement.OVERLAY
            // Leave state-list and ripple opacity to the owner. Preserve foreground contrast
            // on windows with busy content underneath by retaining most of the native fill.
            if (!keepFill && (nativeOutline || boundedPlate) && (stock is GradientDrawable || stock is ColorDrawable ||
                (stock is InsetDrawable && stock.drawable is GradientDrawable))) {
                if (stock.alpha != appliedAlpha) originalAlpha = stock.alpha
                val alpha = (originalAlpha * (1f - 0.42f * strength / 100f)).roundToInt()
                if (stock.alpha != alpha) stock.mutate().alpha = alpha
                appliedAlpha = alpha
            } else restoreAlpha()
        }
        layer.invalidateSelf()
        return true
    }

    @RequiresApi(33)
    fun drawBackdrop(view: View, canvas: Canvas, backdrop: GlassPanelBackdrop, opacity: Float): Boolean {
        if (placement == null) return false
        return drawRefraction(view, canvas, backdrop, opacity)
    }

    private fun drawRefraction(view: View, canvas: Canvas, backdrop: GlassPanelBackdrop, opacity: Float): Boolean {
        if (layer.strength <= 0 || !canvas.isHardwareAccelerated || !view.isShown ||
            !backdrop.isDrawReadyFor(view)) return false
        screenToLocal.reset()
        view.transformMatrixToLocal(screenToLocal)
        return backdrop.draw(canvas, 0f, 0f, shape.left.toFloat(), shape.top.toFloat(),
            shape.right.toFloat(), shape.bottom.toFloat(), radii, layer.strength, layer.dark,
            material.tintFactor, opacity * layer.strength / 100f, screenToLocal)
    }

    /** Samsung draws this plate in onDraw using a moving visible interval. */
    fun drawNotificationControls(view: View, canvas: Canvas, visible: Rect, stock: Drawable, strength: Int,
        backdrop: GlassPanelBackdrop?): Boolean {
        shape.set(visible)
        outline.setEmpty()
        stock.getOutline(outline)
        val radius = outline.radius.takeIf { it > 0f } ?: 24f * view.resources.displayMetrics.density
        radii.fill(radius.coerceAtMost(minOf(shape.width(), shape.height()) * 0.5f))
        layer.dark = view.resources.configuration.isNightModeActive
        layer.strength = strength
        val saved = canvas.save()
        try {
            canvas.clipRect(visible)
            val refracted = backdrop != null && drawRefraction(view, canvas, backdrop, 0.78f)
            material.drawSurface(canvas, shape.left.toFloat(), shape.top.toFloat(), shape.right.toFloat(),
                shape.bottom.toFloat(), radii, strength, layer.dark, fill = !refracted, rim = true,
                profile = GlassMaterial.Profile.SYSTEM_SURFACE)
            return refracted
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun resolvePowerActionCircle(view: View, density: Float): Boolean {
        val icon = view as? ImageView ?: return false
        val artwork = icon.drawable as? VectorDrawable ?: return false
        // The BZI9 action vectors draw a 62-unit disc inside a 64-unit viewport.
        val nativeSize = (64f * density).roundToInt()
        if (abs(artwork.intrinsicWidth - nativeSize) > 1 ||
            abs(artwork.intrinsicHeight - nativeSize) > 1 || artwork.bounds.isEmpty) return false

        actionCircle.set(artwork.bounds)
        actionCircle.inset(actionCircle.width() / 64f, actionCircle.height() / 64f)
        if (!icon.imageMatrix.mapRect(actionCircle)) return false
        actionCircle.offset(icon.paddingLeft.toFloat(), icon.paddingTop.toFloat())
        shape.set(ceil(actionCircle.left).toInt(), ceil(actionCircle.top).toInt(),
            floor(actionCircle.right).toInt(), floor(actionCircle.bottom).toInt())
        if (shape.isEmpty || !shape.containsIn(view)) return false
        radii.fill(minOf(shape.width(), shape.height()) / 2f)
        return true
    }

    private fun Rect.containsIn(view: View): Boolean =
        left >= 0 && top >= 0 && right <= view.width && bottom <= view.height

    private fun resolveShape(view: View, stock: Drawable?, fallbackRadius: Float): Boolean {
        nativeOutline = false
        if (stock is ColorDrawable && stock.color ushr 24 == 0 && fallbackRadius <= 0f) return false
        if (stock != null) {
            outline.setEmpty()
            stock.getOutline(outline)
            if (outline.getRect(shape) && !shape.isEmpty && outline.radius > 0f) {
                radii.fill(outline.radius)
                nativeOutline = true
                return true
            }
            val gradient = when (stock) {
                is GradientDrawable -> stock
                is InsetDrawable -> stock.drawable as? GradientDrawable
                else -> null
            }
            val corners = gradient?.cornerRadii
            if (corners != null && corners.size >= radii.size) {
                shape.set(gradient.bounds)
                if (shape.isEmpty) shape.set(0, 0, view.width, view.height)
                corners.copyInto(radii, endIndex = radii.size)
                nativeOutline = true
                return true
            }
            // The overlay can follow built-in circular buttons without tinting the ripple.
            if (stock is RippleDrawable && fallbackRadius <= 0f) return false
        }
        if (fallbackRadius <= 0f) return false
        shape.set(0, 0, view.width, view.height)
        radii.fill(fallbackRadius)
        return true
    }

    fun restore(view: View) {
        if (placement == Placement.OVERLAY) view.overlay.remove(layer)
        if (placement == Placement.BACKGROUND && view.background === layer) setBackgroundPreservingPadding(view, null)
        placement = null
        restoreAlpha()
        background = null
    }

    private fun restoreAlpha() {
        val before = background ?: return
        if (appliedAlpha != null && before.alpha == appliedAlpha) before.alpha = originalAlpha
        appliedAlpha = null
    }

    private fun setBackgroundPreservingPadding(view: View, drawable: Drawable?) {
        val start = view.paddingStart
        val top = view.paddingTop
        val end = view.paddingEnd
        val bottom = view.paddingBottom
        view.background = drawable
        if (view.paddingStart != start || view.paddingTop != top || view.paddingEnd != end || view.paddingBottom != bottom) {
            view.setPaddingRelative(start, top, end, bottom)
        }
    }

    private inner class Plate : Drawable() {
        val radii = FloatArray(8)
        var dark = false
        var strength = 0
        var fill = false
        private var reported = false

        override fun draw(canvas: Canvas) {
            material.drawSurface(canvas, bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                bounds.bottom.toFloat(), radii, strength, dark, fill = fill, rim = true,
                profile = if (powerActionIcon) GlassMaterial.Profile.POWER_ACTION else GlassMaterial.Profile.SYSTEM_SURFACE)
            if (!reported && !bounds.isEmpty && strength > 0) {
                reported = true
                onDrawn()
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
