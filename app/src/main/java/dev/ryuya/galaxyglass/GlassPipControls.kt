package dev.ryuya.galaxyglass

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.VectorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import dev.ryuya.galaxyglass.compat.DexSymbols
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tracks only the native buttons selected by the PiP layout catalog. */
internal class GlassPipControls {
    private class Button(val effect: GlassSystemSurfaceEffect) {
        var artwork: VectorDrawable? = null
        var disk: VectorDisk? = null
        var stockBlur: Any? = null
        var appliedBlur: Any? = null
        var rows = emptyList<WeakReference<ViewGroup>>()
    }

    private class Row(val originallyClipped: Boolean, var buttons: Int)

    private val buttons = Collections.synchronizedMap(WeakHashMap<View, Button>())
    private val actionRows = WeakHashMap<ViewGroup, Row>()
    private val vector by lazy { runCatching { VectorPaths() }.getOrNull() }
    private var blur: PipBlur? = null

    init {
        val coordinates = Float::class.javaPrimitiveType
        ModernHook.aroundMethod(View::class.java.getMethod("drawableHotspotChanged", coordinates, coordinates)) { chain ->
            val view = chain.thisObject as? View
            if (view != null) buttons[view]?.effect?.setPipHotspot(chain.args[0] as Float, chain.args[1] as Float)
            chain.proceed()
        }
        ModernHook.aroundMethod(View::class.java.getMethod("setPressed", Boolean::class.javaPrimitiveType)) { chain ->
            val view = chain.thisObject as? View
            val result = chain.proceed()
            if (view != null) buttons[view]?.effect?.setPipPressed(view, view.isPressed && view.isEnabled)
            result
        }
        runCatching {
            PipBlur().also { native ->
                ModernHook.aroundMethod(native.setInfo) { chain ->
                    val view = chain.thisObject as? View ?: return@aroundMethod chain.proceed()
                    val button = buttons[view] ?: return@aroundMethod chain.proceed()
                    val stock = chain.args[0]
                    if (stock != null && stock === button.appliedBlur) return@aroundMethod chain.proceed()
                    val clearer = stock?.let { runCatching { native.clearer(it) }.getOrNull() }
                    val result = if (clearer == null) chain.proceed() else chain.proceed(arrayOf(clearer))
                    button.stockBlur = stock
                    button.appliedBlur = clearer
                    result
                }
            }
        }.onSuccess { blur = it }.onFailure {
            HookEntry.log("GLASS_PIP_BLUR_TONE_UNAVAILABLE ${it.javaClass.simpleName}")
        }
    }

    fun attach(view: View, effect: GlassSystemSurfaceEffect) {
        val button = buttons.getOrPut(view) {
            Button(effect).also { binding ->
                val native = blur
                if (view.javaClass.name == ACTION_VIEW) {
                    binding.rows = allowActionExpansion(view)
                }
                if (native != null && view.javaClass.name == ACTION_VIEW) {
                    runCatching {
                        val stock = native.currentInfo(view) ?: return@runCatching
                        val clearer = native.clearer(stock) ?: return@runCatching
                        native.setInfo.invoke(view, clearer)
                        binding.stockBlur = stock
                        binding.appliedBlur = clearer
                    }
                }
            }
        }
        updateArtwork(view, button)
        effect.setPipPressed(view, view.isPressed && view.isEnabled)
    }

    fun refreshArtwork(view: View) {
        buttons[view]?.let { updateArtwork(view, it) }
    }

    private fun updateArtwork(view: View, button: Button) {
        if (view !is ImageButton) return
        val current = view.drawable as? VectorDrawable
        if (current === button.artwork) return
        button.disk?.restore()
        button.artwork = current
        button.disk = current?.let { runCatching { vector?.lighten(it) }.getOrNull() }
        if (button.disk != null) view.invalidate()
    }

    fun detach(view: View) {
        val button = buttons.remove(view) ?: return
        runCatching { button.disk?.restore() }
        releaseActionExpansion(button.rows)
        val native = blur
        if (native != null && button.appliedBlur != null) {
            runCatching {
                if (native.currentInfo(view) === button.appliedBlur) native.setInfo.invoke(view, button.stockBlur)
            }
        }
    }

    private fun allowActionExpansion(view: View): List<WeakReference<ViewGroup>> {
        // The native rows are measured to the resting circle. Their drawing clips
        // crop the pressed ring; their sizes and input bounds can stay intact.
        val rows = generateSequence(view.parent as? ViewGroup) { it.parent as? ViewGroup }
            .takeWhile { row ->
                runCatching { row.resources.getResourceEntryName(row.id) in ACTION_ROW_IDS }.getOrDefault(false)
            }
            .toList()
        synchronized(actionRows) {
            rows.forEach { row ->
                val state = actionRows.getOrPut(row) { Row(row.clipChildren, 0) }
                if (state.buttons++ == 0 && state.originallyClipped) row.clipChildren = false
            }
        }
        return rows.map(::WeakReference)
    }

    private fun releaseActionExpansion(rows: List<WeakReference<ViewGroup>>) {
        synchronized(actionRows) {
            for (reference in rows) {
                val row = reference.get() ?: continue
                val state = actionRows[row] ?: continue
                if (--state.buttons == 0) {
                    if (state.originallyClipped && !row.clipChildren) row.clipChildren = true
                    actionRows.remove(row)
                }
            }
        }
    }

    private class VectorDisk(
        val drawable: VectorDrawable,
        val path: Any,
        val alpha: java.lang.reflect.Method,
        val writer: java.lang.reflect.Method,
        val original: Float,
    ) {
        fun restore() {
            if (alpha.invoke(path) == LIGHT_DISK_ALPHA) writer.invoke(path, original)
            drawable.invalidateSelf()
        }
    }

    // This is resolved inside SystemUI. Unsupported frameworks keep the original vector.
    @SuppressLint("SoonBlockedPrivateApi")
    private class VectorPaths {
        private val state = VectorDrawable::class.java.getDeclaredField("mVectorState").accessible()
        private val group = state.type.getDeclaredField("mRootGroup").accessible()
        private val children = group.type.getDeclaredField("mChildren").accessible()
        private val fullPath = DexSymbols.findClass("android.graphics.drawable.VectorDrawable\$VFullPath")
        private val color = fullPath.getDeclaredMethod("getFillColor").apply { isAccessible = true }
        private val alpha = fullPath.getDeclaredMethod("getFillAlpha").apply { isAccessible = true }
        private val writer = fullPath.getDeclaredMethod("setFillAlpha", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }

        private fun firstPath(drawable: VectorDrawable): Any? =
            (children.get(group.get(state.get(drawable))) as? List<*>)?.firstOrNull()

        fun lighten(drawable: VectorDrawable): VectorDisk? {
            val original = firstPath(drawable) ?: return null
            // The Samsung 24 dp artwork has a black 18% disc as its first path.
            if (!fullPath.isInstance(original) || color.invoke(original) != Color.BLACK ||
                abs((alpha.invoke(original) as Float) - .18f) > .001f) return null
            drawable.mutate()
            val disk = firstPath(drawable) ?: return null
            val originalAlpha = alpha.invoke(disk) as Float
            writer.invoke(disk, LIGHT_DISK_ALPHA)
            drawable.invalidateSelf()
            return VectorDisk(drawable, disk, alpha, writer, originalAlpha)
        }
    }

    private class PipBlur {
        private val info = DexSymbols.findClass("android.view.SemBlurInfo")
        private val builder = DexSymbols.findClass("android.view.SemBlurInfo\$Builder")
        private val curve = info.getMethod("getColorCurve")
        private val fields = listOf("mSaturation", "mCurveBias", "mMinX", "mMaxX", "mMinY", "mMaxY")
            .map { curve.returnType.getField(it) }
        private val stock = builder.getMethod("getBlurPresetAttrs", Int::class.javaPrimitiveType)
            .invoke(null, 116) as FloatArray
        private val attachedInfo = View::class.java.getDeclaredField("mBlurInfo").accessible()
        val setInfo = View::class.java.getMethod("semSetBlurInfo", info)
        private val mode = info.getMethod("getBlurMode")
        private val radius = info.getMethod("getBlurRadius")
        private val corners = info.getMethod("getBackgroundBlurCornerRadius", FloatArray::class.java)
        private val background = info.getMethod("getBackgroundBlurColor")
        private val constructor = builder.getConstructor(Int::class.javaPrimitiveType)
        private val setRadius = builder.getMethod("setRadius", Int::class.javaPrimitiveType)
        private val float = Float::class.javaPrimitiveType
        private val setCorners = builder.getMethod("setBackgroundCornerRadius", float, float, float, float)
        private val setCurve = builder.getMethod("setColorCurve", float, float, float, float, float, float)
        private val build = builder.getMethod("build")

        fun currentInfo(view: View): Any? = attachedInfo.get(view)

        fun clearer(input: Any): Any? {
            if (mode.invoke(input) != 0 || (background.invoke(input) as Int) ushr 24 != 0) return null
            val currentCurve = curve.invoke(input) ?: return null
            if (fields.indices.any { abs(fields[it].getFloat(currentCurve) - stock[it + 1]) > .001f }) return null
            val oldRadius = radius.invoke(input) as Int
            if (oldRadius <= 0) return null
            val shape = FloatArray(4)
            corners.invoke(input, shape)
            val next = constructor.newInstance(0)
            setRadius.invoke(next, (oldRadius * .64f).roundToInt().coerceAtLeast(1))
            setCorners.invoke(next, shape[0], shape[1], shape[2], shape[3])
            // Keep a slight luminance reduction behind white glyphs and retain video color.
            setCurve.invoke(next, .08f, 0f, 0f, 255f, 0f, 226f)
            return build.invoke(next)
        }
    }

    private companion object {
        const val ACTION_VIEW = "com.android.wm.shell.pip2.phone.PipMenuActionView"
        const val LIGHT_DISK_ALPHA = .04f
        val ACTION_ROW_IDS = setOf("actions_group", "actions_container", "menu_container")

        fun java.lang.reflect.Field.accessible() = apply { isAccessible = true }
    }
}
