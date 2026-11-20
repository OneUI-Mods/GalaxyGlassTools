package dev.ryuya.galaxyglass

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.annotation.RequiresApi
import dev.ryuya.galaxyglass.compat.DexSymbols
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal const val SAMSUNG_TOOLTIP = "samsung_tooltip"

/** Applies a single highlight to Samsung's masked tooltip parts, including their native arrow. */
@RequiresApi(33)
internal class GlassSamsungTips(
    private val strength: () -> Int,
    private val allowsSource: () -> Boolean = { true },
    private val onDrawn: () -> Unit,
) {
    private val parts = WeakHashMap<ImageView, Part>()
    private var reported = false
    private var failureReported = false

    init {
        val tip = DexSymbols.findClass("com.samsung.android.widget.SemTipPopup")
        val parent = tip.getDeclaredField("mParentView").apply { isAccessible = true }
        val balloon = tip.getDeclaredField("mBalloonPanel").apply { isAccessible = true }
        val bubbleImage = tip.getDeclaredField("mBubbleBackground").apply { isAccessible = true }
        val balloonImages = listOf("mBalloonBg1", "mBalloonBg2").map {
            tip.getDeclaredField(it).apply { isAccessible = true }
        }
        val show = tip.getDeclaredMethod("show", java.lang.Integer.TYPE)
        ModernHook.aroundMethod(show) { chain ->
            val result = chain.proceed()
            if (Looper.getMainLooper().isCurrentThread && strength() > 0 && allowsSource()) runCatching {
                val instance = chain.thisObject
                val source = parent.get(instance) as? View ?: return@runCatching
                if (!allowsGlass(source)) return@runCatching
                (bubbleImage.get(instance) as? ImageView)?.let { bind(it, it, source, image = true) }
                val panel = balloon.get(instance) as? View ?: return@runCatching
                for (field in balloonImages) {
                    (field.get(instance) as? ImageView)?.let { bind(it, panel, source, image = false) }
                }
            }.onFailure {
                if (!failureReported) {
                    failureReported = true
                    HookEntry.log("GLASS_SAMSUNG_TIP_FAILED ${it.javaClass.simpleName}")
                }
            }
            result
        }
    }

    fun refresh() {
        for ((view, part) in parts.entries.toList()) {
            if (strength() <= 0) part.restore(view) else part.reapply(view)
        }
    }

    private fun allowsGlass(view: View): Boolean {
        if (!view.isAttachedToWindow || view.display?.displayId != 0) return false
        val window = view.rootView.layoutParams as? WindowManager.LayoutParams ?: return false
        return window.flags and WindowManager.LayoutParams.FLAG_SECURE == 0
    }

    private fun bind(view: ImageView, panel: View, source: View, image: Boolean) {
        val stock = (if (image) view.drawable else view.background) ?: return
        if (stock is Part) return
        if (parts[view]?.reapply(view) == true) return
        val part = Part(view, panel, source, stock, image)
        parts[view] = part
        part.install(view)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                val imageView = view as ImageView
                if (parts[imageView] === part) parts.remove(imageView)
                part.restore(imageView)
                view.removeOnAttachStateChangeListener(this)
            }
        })
    }

    private inner class Part(
        view: ImageView,
        panel: View,
        source: View,
        private val stock: Drawable,
        private val image: Boolean,
    ) : DrawableWrapper(stock) {
        private val view = WeakReference(view)
        private val panel = WeakReference(panel)
        private val source = WeakReference(source)
        private val layer = Paint()
        private val color = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        private val map = Matrix()
        private val panelPosition = IntArray(2)
        private var gradient: LinearGradient? = null
        private var height = 0
        private var dark = false

        fun restore(view: ImageView) {
            if (image && view.drawable === this) view.setImageDrawable(stock)
            if (!image && view.background === this) view.background = stock
        }

        fun install(view: ImageView) {
            if (image) view.setImageDrawable(this) else view.background = this
            stock.callback = this
        }

        fun reapply(view: ImageView): Boolean {
            val current = if (image) view.drawable else view.background
            if (current === this) {
                view.invalidate()
                return true
            }
            if (current !== stock) return false
            install(view)
            return true
        }

        override fun draw(canvas: Canvas) {
            val host = view.get()
            val origin = source.get()
            val region = panel.get()
            val level = strength().coerceIn(0, 100)
            if (level == 0 || host == null || origin == null || region == null || region.height <= 0 ||
                bounds.isEmpty || !canvas.isHardwareAccelerated || !allowsGlass(host) || !allowsGlass(origin)) {
                super.draw(canvas)
                return
            }
            val night = host.resources.configuration.isNightModeActive
            val shader = shade(region.height, night)
            region.getLocationOnScreen(panelPosition)
            map.setTranslate(panelPosition[0].toFloat(), panelPosition[1].toFloat())
            host.transformMatrixToLocal(map)
            shader.setLocalMatrix(map)
            color.shader = shader
            color.alpha = 255 * level / 100
            layer.alpha = 255 - 42 * level / 100
            val save = canvas.saveLayer(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                bounds.bottom.toFloat(), layer)
            try {
                super.draw(canvas)
                canvas.drawRect(bounds, color)
            } finally {
                canvas.restoreToCount(save)
            }
            if (!reported) {
                reported = true
                onDrawn()
            }
        }

        private fun shade(panelHeight: Int, night: Boolean): LinearGradient {
            val current = gradient
            if (current != null && height == panelHeight && dark == night) return current
            height = panelHeight
            dark = night
            val colors = if (night) intArrayOf(0x65f0f7ff, 0x15172838, 0x620a1625)
                else intArrayOf(0x78ffffff, 0x0df1f7ff, 0x4b91a6c0)
            return LinearGradient(0f, 0f, 0f, panelHeight.toFloat(), colors, floatArrayOf(0f, .48f, 1f),
                Shader.TileMode.CLAMP).also { gradient = it }
        }
    }
}
