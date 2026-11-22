package dev.ryuya.galaxyglass

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Trace
import android.view.Display
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.annotation.RequiresApi
import dev.ryuya.galaxyglass.compat.DexSymbols
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Composes only the wallpaper mirror into a dock-sized GPU stream while Home is visible. */
@RequiresApi(33)
internal class GlassDockStream(
    host: RelativeLayout,
    private val main: Handler,
    private val worker: Executor,
    private val material: GlassMaterial,
    private val changed: () -> Unit,
) {
    private data class SourceArea(val crop: Rect, val screen: Rect, val refreshRate: Float)
    private data class Update(val bounds: Rect, val radius: Float, val strength: Int, val dark: Boolean)

    private val owner = WeakReference(host)
    private val density = host.resources.displayMetrics.density
    private val margin = ceil(44 * density).toInt()
    private val movementMargin = ceil(20 * density).toInt()
    private val transform = Matrix()
    private val physicalBounds = RectF()
    private val keyguard = host.context.getSystemService(KeyguardManager::class.java)
    // Resource delivery must run even after GlassHost cancels its own callbacks on hot reload.
    private val resourceDelivery = Handler(main.looper)
    private var session: Session? = null
    private var failed = false
    private var pending: Update? = null
    private var checkingKeyguard = false
    private var accessGeneration = 0L
    private val windowReleases = mutableListOf<Runnable>()

    val supported: Boolean get() = !failed
    val ownsPlate: Boolean get() = session != null

    fun update(bounds: Rect, radius: Float, strength: Int, dark: Boolean) {
        val host = visibleHost()
        if (host == null) {
            close(afterTransition = true)
            return
        }
        if (session == null && (bounds.isEmpty || !host.hasWindowFocus())) return
        pending = Update(Rect(bounds), radius, strength, dark)
        checkKeyguard()
    }

    private fun visibleHost(): RelativeLayout? = owner.get()?.takeIf {
        !failed && it.isAttachedToWindow && it.isShown && it.windowVisibility == View.VISIBLE
    }

    private fun checkKeyguard() {
        if (checkingKeyguard || pending == null) return
        checkingKeyguard = true
        val generation = accessGeneration
        try {
            worker.execute {
                Trace.beginSection("GlassDockStream.keyguard")
                val unlocked = try {
                    runCatching { keyguard?.isKeyguardLocked == false }.getOrDefault(false)
                } finally {
                    Trace.endSection()
                }
                main.post {
                    checkingKeyguard = false
                    if (generation == accessGeneration) {
                        val update = pending
                        pending = null
                        if (!unlocked) close()
                        else if (update != null) updateUnlocked(update)
                    }
                    checkKeyguard()
                }
            }
        } catch (_: RejectedExecutionException) {
            checkingKeyguard = false
            close()
        }
    }

    private fun updateUnlocked(update: Update) {
        val host = visibleHost() ?: return
        val (bounds, radius, strength, dark) = update
        // Recents and the shade can claim focus while the user can still see the dock.
        if (bounds.isEmpty || !host.hasWindowFocus()) return
        val display = host.display ?: return
        val screen = host.context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds ?: return
        if (screen.isEmpty) return
        transform.reset()
        host.transformMatrixToGlobal(transform)
        physicalBounds.set(bounds)
        transform.mapRect(physicalBounds)
        val crop = Rect()
        physicalBounds.roundOut(crop)
        crop.inset(-margin, -margin)
        if (!crop.intersect(0, 0, screen.width(), screen.height())) return
        val rate = display.supportedModes.maxOfOrNull { it.refreshRate }?.coerceAtMost(120f) ?: display.refreshRate
        val current = session
        if (current != null && current.source.screen == screen && current.source.refreshRate == rate &&
            current.source.crop.contains(crop)) {
            current.update(host, bounds, shape(bounds, current.source.crop, radius, strength, dark))
            return
        }
        // Keep the same source through the scale and translation of a return to Home.
        val bottomBand = Rect(0, screen.height() * 3 / 4, screen.width(), screen.height())
        val sourceCrop = if (bottomBand.contains(crop)) bottomBand else crop.apply {
            inset(-movementMargin, -movementMargin)
            intersect(0, 0, screen.width(), screen.height())
        }
        releaseSession(afterTransition = true)
        val next = Session(
            host, SourceArea(sourceCrop, screen, rate), bounds,
            shape(bounds, sourceCrop, radius, strength, dark),
        )
        session = next
        try {
            next.attach(host)
            changed()
        } catch (error: Exception) {
            fail(next, error)
        }
    }

    private fun shape(bounds: Rect, crop: Rect, radius: Float, strength: Int, dark: Boolean) =
        GlassDockRenderer.Shape(
            bounds.width(), bounds.height(), physicalBounds.left - crop.left, physicalBounds.top - crop.top,
            physicalBounds.width() / bounds.width(), physicalBounds.height() / bounds.height(),
            crop.width(), crop.height(), radius, density, strength / 100f, dark,
        )

    fun align() {
        val active = session ?: return
        if (owner.get()?.hasWindowFocus() == true) active.alignOutput()
    }

    fun close(afterTransition: Boolean = false) {
        accessGeneration++
        pending = null
        releaseSession(afterTransition)
        if (!afterTransition) {
            for (release in windowReleases.toList()) {
                main.removeCallbacks(release)
                release.run()
            }
        }
    }

    private fun releaseSession(afterTransition: Boolean) {
        val active = session ?: return
        session = null
        active.close(afterTransition)
        changed()
    }

    private fun releaseWindow(window: Presentation?, display: VirtualDisplay?, afterTransition: Boolean) {
        if (window == null && display == null) return
        val release = object : Runnable {
            override fun run() {
                windowReleases.remove(this)
                Trace.beginSection("GlassDockStream.releaseWindow")
                try {
                    runCatching { window?.dismiss() }
                    runCatching { display?.release() }
                } finally {
                    Trace.endSection()
                }
            }
        }
        if (!afterTransition) {
            release.run()
            return
        }
        windowReleases += release
        if (!main.postDelayed(release, WINDOW_RELEASE_DELAY_MS)) release.run()
    }

    private fun fail(active: Session, error: Exception) {
        if (session !== active) return
        failed = true
        HookEntry.log("GLASS_DOCK_STREAM_UNAVAILABLE ${error.javaClass.simpleName} ${error.message}")
        close()
    }

    private inner class Session(
        host: RelativeLayout,
        val source: SourceArea,
        initialBounds: Rect,
        initialShape: GlassDockRenderer.Shape,
    ) : SurfaceHolder.Callback {
        private val outputBounds = Rect(initialBounds)
        private val output = SurfaceView(host.context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // The compositor places this behind the launcher window. Native icons and the rim
            // draw on top, independently of when the next wallpaper frame arrives.
            holder.setFormat(PixelFormat.TRANSLUCENT)
            holder.addCallback(this@Session)
        }
        private val container = FrameLayout(host.context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(output, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            foreground = object : Drawable() {
                override fun draw(canvas: Canvas) {
                    val optics = shape
                    material.draw(canvas, 0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(),
                        optics.corner, optics.corner, (optics.strength * 100).roundToInt(), optics.dark)
                }

                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(filter: ColorFilter?) = Unit
                @Deprecated("Required by Drawable")
                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            }
        }
        private var thread: HandlerThread? = null
        private var graphics: Handler? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var presentation: Presentation? = null
        private var mirror: SurfaceControl? = null
        @Volatile private var mirrorGeneration = 0L
        @Volatile private var attached = false
        @Volatile private var closed = false
        @Volatile private var shape = initialShape
        private val renderer = GlassDockRenderer()
        private var drewFrame = false
        private val applyStyle = Runnable { present(newFrame = false) }
        private var refractionFade: ValueAnimator? = null
        private val startTimeout = Runnable {
            if (!closed && !ready) fail(this, IllegalStateException("Wallpaper stream produced no frames"))
        }
        private var ready = false

        fun attach(host: RelativeLayout) {
            host.addView(container, 0, layout(host))
        }

        private fun layout(host: RelativeLayout) =
            RelativeLayout.LayoutParams(outputBounds.width(), outputBounds.height()).apply {
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                leftMargin = outputBounds.left - host.paddingLeft
                topMargin = outputBounds.top - host.paddingTop
            }

        fun update(host: RelativeLayout, bounds: Rect, shape: GlassDockRenderer.Shape) {
            if (outputBounds != bounds) {
                outputBounds.set(bounds)
                container.layoutParams = layout(host)
            }
            val next = shape.copy(edgeMix = this.shape.edgeMix)
            if (this.shape == next) return
            this.shape = next
            container.invalidate()
            requestStyle()
        }

        fun alignOutput() {
            if (!output.isAttachedToWindow || output.width != shape.width || output.height != shape.height) return
            transform.reset()
            output.transformMatrixToGlobal(transform)
            physicalBounds.set(0f, 0f, output.width.toFloat(), output.height.toFloat())
            transform.mapRect(physicalBounds)
            val old = shape
            val x = physicalBounds.left - source.crop.left
            val y = physicalBounds.top - source.crop.top
            val scaleX = physicalBounds.width() / old.width
            val scaleY = physicalBounds.height() / old.height
            if (old.leftInSource == x && old.topInSource == y && old.screenScaleX == scaleX && old.screenScaleY == scaleY) {
                return
            }
            shape = old.copy(leftInSource = x, topInSource = y, screenScaleX = scaleX, screenScaleY = scaleY)
            requestStyle()
        }

        private fun requestStyle() {
            graphics?.let { if (!it.hasCallbacks(applyStyle)) it.post(applyStyle) }
        }

        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (closed || graphics != null || !holder.surface.isValid) return
            val target = holder.surface
            val crop = source.crop
            val sampleWidth = (crop.width() + 1) / 2
            val sampleHeight = (crop.height() + 1) / 2
            val renderThread = HandlerThread("OneUiDockGlass").apply { start() }
            thread = renderThread
            val handler = Handler(renderThread.looper)
            graphics = handler
            handler.post {
                if (!closed) {
                    try {
                        val source = renderer.start(target, sampleWidth, sampleHeight)
                        renderer.observe(handler) { present(newFrame = true) }
                        main.post {
                            if (!closed) try {
                                showSource(source, sampleWidth, sampleHeight)
                            } catch (error: Exception) {
                                fail(this, error)
                            }
                        }
                    } catch (error: Exception) {
                        main.post { fail(this, error) }
                    }
                }
            }
        }

        private fun showSource(surface: Surface, width: Int, height: Int) {
            val context = container.context
            val displays = checkNotNull(context.getSystemService(DisplayManager::class.java))
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            val dpi = context.resources.displayMetrics.densityDpi
            val display = if (Build.VERSION.SDK_INT >= 34) {
                val config = VirtualDisplayConfig.Builder("One UI dock wallpaper", width, height, dpi)
                    .setFlags(flags)
                    .setSurface(surface)
                    .setRequestedRefreshRate(source.refreshRate)
                    .build()
                displays.createVirtualDisplay(config)
            } else {
                displays.createVirtualDisplay("One UI dock wallpaper", width, height, dpi, surface, flags)
            }
            virtualDisplay = checkNotNull(display) { "Wallpaper display not available" }
            val window = Presentation(context, display.display)
            presentation = window
            val target = SurfaceView(window.context).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        if (!closed) try {
                            requestMirror(this@apply, width, height)
                        } catch (error: Exception) {
                            fail(this@Session, error)
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        mirrorGeneration++
                        attached = false
                        runCatching { detachMirror() }
                    }
                })
            }
            window.setContentView(target, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            window.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                attributes = attributes.apply {
                    this.width = MATCH_PARENT
                    this.height = MATCH_PARENT
                    fitInsetsTypes = 0
                    dimAmount = 0f
                    windowAnimations = 0
                }
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            window.show()
            window.window?.insetsController?.hide(WindowInsets.Type.systemBars())
            main.postDelayed(startTimeout, 2_500L)
        }

        private fun requestMirror(target: SurfaceView, width: Int, height: Int) {
            val generation = ++mirrorGeneration
            worker.execute {
                if (closed || generation != mirrorGeneration) return@execute
                Trace.beginSection("GlassDockStream.requestMirror")
                val result = try {
                    runCatching {
                        if (keyguard?.isKeyguardLocked != false) return@runCatching null
                        val manager = DexSymbols.findClass("android.view.WindowManagerGlobal")
                            .getMethod("getWindowManagerService").invoke(null)
                        val method = DexSymbols.findClass("android.view.IWindowManager")
                            .getMethod("mirrorWallpaperSurface", Int::class.javaPrimitiveType)
                        checkNotNull(method.invoke(manager, Display.DEFAULT_DISPLAY) as? SurfaceControl) {
                            "Visible wallpaper not available"
                        }
                    }
                } finally {
                    Trace.endSection()
                }
                val layer = result.getOrNull()
                if (!resourceDelivery.post {
                        if (closed || generation != mirrorGeneration || !target.holder.surface.isValid) {
                            layer?.release()
                            return@post
                        }
                        try {
                            val available = result.getOrThrow()
                            if (available == null) this@GlassDockStream.close()
                            else attachMirror(available, target, width, height)
                        } catch (error: Exception) {
                            if (layer != null && mirror !== layer) layer.release()
                            fail(this@Session, error)
                        }
                    }) {
                    layer?.release()
                }
            }
        }

        private fun attachMirror(layer: SurfaceControl, target: SurfaceView, width: Int, height: Int) {
            detachMirror()
            check(layer.isValid) { "Visible wallpaper not available" }
            mirror = layer
            val parent = SurfaceView::class.java.getMethod("getRenderingSurfaceControl").invoke(target) as? SurfaceControl
            check(parent != null && parent.isValid) { "Wallpaper target not available" }
            val crop = source.crop
            val scaleX = width.toFloat() / crop.width()
            val scaleY = height.toFloat() / crop.height()
            SurfaceControl.Transaction().use {
                it.reparent(layer, parent)
                    .setCrop(layer, Rect(0, 0, source.screen.width(), source.screen.height()))
                    .setScale(layer, scaleX, scaleY)
                    .setPosition(layer, -crop.left * scaleX, -crop.top * scaleY)
                    .setVisibility(layer, true)
                    .apply()
            }
            attached = true
            HookEntry.log("GLASS_DOCK_STREAM_MIRRORED ${width}x$height at ${virtualDisplay?.display?.refreshRate} Hz")
        }

        private fun detachMirror() {
            val surface = mirror ?: return
            mirror = null
            try {
                SurfaceControl.Transaction().use { it.reparent(surface, null).apply() }
            } finally {
                surface.release()
            }
        }

        private fun present(newFrame: Boolean) {
            if (closed || !attached) return
            try {
                if (renderer.draw(shape, newFrame) && !drewFrame) {
                    drewFrame = true
                    main.post {
                        if (!closed) {
                            ready = true
                            main.removeCallbacks(startTimeout)
                            refractionFade = ValueAnimator.ofFloat(0f, 1f).apply {
                                duration = 100L
                                interpolator = DecelerateInterpolator()
                                addUpdateListener {
                                    shape = shape.copy(edgeMix = it.animatedValue as Float)
                                    requestStyle()
                                }
                                start()
                            }
                            HookEntry.log("GLASS_DOCK_STREAM_DRAWN")
                        }
                    }
                }
            } catch (error: Exception) {
                main.post { fail(this, error) }
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (!closed) main.post {
                if (session === this) this@GlassDockStream.close(afterTransition = true)
            }
        }

        fun close(afterTransition: Boolean) {
            closed = true
            attached = false
            ready = false
            main.removeCallbacks(startTimeout)
            refractionFade?.cancel()
            refractionFade = null
            runCatching { detachMirror() }
            val oldWindow = presentation
            val oldDisplay = virtualDisplay
            presentation = null
            virtualDisplay = null
            graphics?.post { renderer.close() }
            (container.parent as? RelativeLayout)?.removeView(container)
            thread?.quitSafely()
            graphics = null
            thread = null
            // The mirror and dock output are already detached. Dismissing the source
            // Presentation can wait on HWUI, so let the launcher finish its transition first.
            releaseWindow(oldWindow, oldDisplay, afterTransition)
        }
    }

    private companion object {
        const val WINDOW_RELEASE_DELAY_MS = 350L
    }
}
