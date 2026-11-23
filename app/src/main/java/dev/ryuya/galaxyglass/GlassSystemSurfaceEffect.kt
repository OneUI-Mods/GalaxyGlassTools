package dev.ryuya.galaxyglass

import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Styles the native plate without replacing drawables that SystemUI may tint or cast. */
@RequiresApi(33)
internal class GlassSystemSurfaceEffect(
    private val material: GlassMaterial,
    private val powerActionIcon: Boolean,
    private val pipButton: Boolean = false,
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
    private var appliedTint = material.tint
    private var pipPressed = false
    private var pressAnimator: ValueAnimator? = null
    private val pressMatrix by lazy { Matrix() }
    private var ownsPressMatrix = false

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
        val resolved = when {
            powerActionIcon -> resolvePowerActionCircle(view, density)
            pipButton -> resolvePipButtonCircle(view, stock, density)
            else -> resolveShape(view, stock, density * fallbackDp)
        }
        if (!resolved) {
            restore(view)
            return false
        }
        val dark = view.resources.configuration.isNightModeActive
        val appearanceChanged = !pipButton || placement == null || layer.strength != strength ||
            layer.dark != dark || appliedTint != material.tint || layer.bounds != shape ||
            !layer.radii.contentEquals(radii)
        appliedTint = material.tint
        layer.strength = strength
        layer.dark = dark
        layer.setBounds(shape)
        radii.copyInto(layer.radii)

        if (stock == null && !powerActionIcon && !pipButton && !keepFill) {
            if (placement == Placement.OVERLAY) view.overlay.remove(layer)
            layer.fill = true
            if (view.background !== layer) setBackgroundPreservingPadding(view, layer)
            placement = Placement.BACKGROUND
        } else {
            layer.fill = pipButton
            if (placement != Placement.OVERLAY) view.overlay.add(layer)
            placement = Placement.OVERLAY
            // Leave state-list and ripple opacity to the owner. Preserve foreground contrast
            // on windows with busy content underneath by retaining most of the native fill.
            if (!keepFill && !pipButton && (nativeOutline || boundedPlate) && (stock is GradientDrawable || stock is ColorDrawable ||
                (stock is InsetDrawable && stock.drawable is GradientDrawable))) {
                if (stock.alpha != appliedAlpha) originalAlpha = stock.alpha
                val alpha = (originalAlpha * (1f - 0.42f * strength / 100f)).roundToInt()
                if (stock.alpha != alpha) stock.mutate().alpha = alpha
                appliedAlpha = alpha
            } else restoreAlpha()
        }
        if (appearanceChanged) layer.invalidateSelf()
        return true
    }

    private fun resolvePipButtonCircle(view: View, stock: Drawable?, density: Float): Boolean {
        val transparent = stock == null || (stock is ColorDrawable && stock.alpha == 0)
        val samsungIcon = (view as? ImageButton)?.drawable as? VectorDrawable
        val samsungSize = (24f * density).roundToInt()
        // Samsung's top controls draw their own 24 dp discs inside larger touch targets.
        if (transparent && samsungIcon != null && abs(samsungIcon.intrinsicWidth - samsungSize) <= 1 &&
            abs(samsungIcon.intrinsicHeight - samsungSize) <= 1 && !samsungIcon.bounds.isEmpty &&
            resolveArtworkBounds(view, samsungIcon)) {
            radii.fill(minOf(shape.width(), shape.height()) / 2f)
            return true
        }
        val size = minOf(view.width, view.height)
        // PiP2 already blurs a disc sized to this view. Follow its size when the menu resizes.
        val diameter = if (view.javaClass.name == "com.android.wm.shell.pip2.phone.PipMenuActionView") size
            else minOf(36f * density, size - 4f * density).roundToInt()
        if (diameter <= 0) return false
        val left = (view.width - diameter) / 2
        val top = (view.height - diameter) / 2
        shape.set(left, top, left + diameter, top + diameter)
        radii.fill(diameter / 2f)
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

        if (!resolveArtworkBounds(icon, artwork, 1f / 64f)) return false
        radii.fill(minOf(shape.width(), shape.height()) / 2f)
        return true
    }

    private fun resolveArtworkBounds(icon: ImageView, artwork: Drawable, inset: Float = 0f): Boolean {
        actionCircle.set(artwork.bounds)
        actionCircle.inset(actionCircle.width() * inset, actionCircle.height() * inset)
        if (!icon.imageMatrix.mapRect(actionCircle)) return false
        actionCircle.offset(icon.paddingLeft.toFloat(), icon.paddingTop.toFloat())
        shape.set(ceil(actionCircle.left).toInt(), ceil(actionCircle.top).toInt(),
            floor(actionCircle.right).toInt(), floor(actionCircle.bottom).toInt())
        return !shape.isEmpty && shape.containsIn(icon)
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

    fun setPipHotspot(x: Float, y: Float) {
        val bounds = layer.bounds
        if (bounds.isEmpty) return
        layer.touchX = ((x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        layer.touchY = ((y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        if (pipPressed) layer.invalidateSelf()
    }

    fun setPipPressed(view: View, pressed: Boolean) {
        if (!pipButton || pipPressed == pressed || placement == null) return
        pipPressed = pressed
        pressAnimator?.cancel()
        pressAnimator = null
        val target = if (pressed) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            updatePipPress(view, target, animate = false)
            return
        }
        // A quick tap still gets a visible release, even if both events arrive in one frame.
        if (pressed) updatePipPress(view, maxOf(layer.press, .42f), animate = true)
        val button = WeakReference(view)
        pressAnimator = ValueAnimator.ofFloat(layer.press, target).apply {
            duration = if (pressed) 108L else 205L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                button.get()?.let { view -> updatePipPress(view, it.animatedValue as Float, animate = true) }
            }
            start()
        }
    }

    private fun updatePipPress(view: View, amount: Float, animate: Boolean) {
        layer.press = amount
        layer.invalidateSelf()
        if (!animate || amount <= 0f) {
            clearPipScale(view)
            return
        }
        // Scale the rendered control as a whole, including its glyph and native backdrop.
        // The RenderNode animation matrix is separate from the view's input transform.
        val current = view.animationMatrix
        if (ownsPressMatrix && current != pressMatrix) ownsPressMatrix = false
        if ((current != null && !ownsPressMatrix) || view.animation?.hasEnded() == false) return
        val scale = 1f + amount * .14f
        pressMatrix.setScale(scale, scale, layer.bounds.exactCenterX(), layer.bounds.exactCenterY())
        view.animationMatrix = pressMatrix
        ownsPressMatrix = true
    }

    private fun clearPipScale(view: View) {
        if (!ownsPressMatrix) return
        if (view.animationMatrix == pressMatrix) view.animationMatrix = null
        ownsPressMatrix = false
    }

    fun restore(view: View) {
        pressAnimator?.cancel()
        pressAnimator = null
        pipPressed = false
        layer.press = 0f
        layer.touchX = .5f
        layer.touchY = .5f
        clearPipScale(view)
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
        var press = 0f
        var touchX = .5f
        var touchY = .5f
        private var reported = false
        private val pressLight by lazy { GlassPipPressLight() }

        override fun draw(canvas: Canvas) {
            material.drawSurface(canvas, bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                bounds.bottom.toFloat(), radii, strength, dark, fill = fill, rim = true,
                profile = when {
                    pipButton -> GlassMaterial.Profile.PIP_CONTROL
                    powerActionIcon -> GlassMaterial.Profile.POWER_ACTION
                    else -> GlassMaterial.Profile.SYSTEM_SURFACE
                })
            if (pipButton && press > 0f && !bounds.isEmpty) {
                pressLight.draw(canvas, bounds, touchX, touchY, press, dark)
            }
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
