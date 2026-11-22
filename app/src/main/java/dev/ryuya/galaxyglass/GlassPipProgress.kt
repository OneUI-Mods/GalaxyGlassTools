package dev.ryuya.galaxyglass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import dev.ryuya.galaxyglass.compat.compatibleDeclaredField
import dev.ryuya.galaxyglass.compat.compatibleDeclaredMethod
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.roundToInt

/** Reuses the session selected by Shell for this PiP, including the correct Android user. */
internal class GlassPipProgress private constructor(
    private val ownerView: ViewGroup,
    private val menu: FrameLayout,
    private val actions: View,
    private val mediaOwner: Any,
    private val controllerField: Field,
    private val main: Handler,
    private val allowed: () -> Boolean,
    private val input: GlassPipSeekInput?,
    private val allowTouches: Field,
    private val allowTimeout: Field,
    private val deferMenuHide: Method,
) {
    private val bar = PlaybackBar(menu.context)
    private val actionBounds = Rect()
    private val touchBounds = Rect()
    private var active = false
    private var dragging = false
    private var dragSession: MediaSession.Token? = null
    private var controller: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var playback: PlaybackState? = null
    private var duration = 0L
    private val tick = Runnable { updateSession() }

    init {
        val margin = bar.dp(12)
        menu.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar.dp(30),
            Gravity.BOTTOM).apply { setMargins(margin, 0, margin, bar.dp(6)) })
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragging = true
                dragSession = controller?.sessionToken
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                if (!bar.cancelled && dragSession == controller?.sessionToken) seek(seekBar.progress)
                dragSession = null
                render()
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                bar.invalidate()
                if (fromUser && !dragging) seek(progress)
            }
        })
        input?.register(ownerView, this)
    }

    fun acceptsSeekGesture(): Boolean =
        Looper.myLooper() == main.looper && active && allowed() && bar.isShown &&
            bar.isEnabled && menu.alpha > 0f && allowTouches.getBoolean(ownerView)

    fun beginSeekGesture(event: MotionEvent): Boolean {
        if (!acceptsSeekGesture()) return false
        locateSeekBar()
        if (event.x < touchBounds.left || event.x >= touchBounds.right ||
            event.y < touchBounds.top || event.y >= touchBounds.bottom) return false
        return deliverSeekGesture(event, false)
    }

    fun deliverSeekGesture(event: MotionEvent, cancel: Boolean): Boolean {
        locateSeekBar()
        val local = MotionEvent.obtain(event)
        try {
            local.offsetLocation(-touchBounds.left.toFloat(), -touchBounds.top.toFloat())
            if (cancel) local.action = MotionEvent.ACTION_CANCEL
            if (allowTimeout.getBoolean(ownerView)) deferMenuHide.invoke(ownerView, 2000)
            return bar.dispatchTouchEvent(local)
        } finally {
            local.recycle()
        }
    }

    private fun locateSeekBar() {
        bar.getDrawingRect(touchBounds)
        if (bar.parent === menu) ownerView.offsetDescendantRectToMyCoords(bar, touchBounds)
    }

    fun updateVisibility() {
        val visible = allowed() && menu.isShown && menu.alpha > 0f &&
            menu.width >= bar.dp(112) && menu.height >= bar.dp(84)
        if (visible == active) return
        active = visible
        if (visible) updateSession() else suspendUpdates()
    }

    fun close() {
        if (Looper.myLooper() != main.looper) {
            main.post(::close)
            return
        }
        active = false
        input?.unregister(ownerView)
        suspendUpdates()
        bar.setOnSeekBarChangeListener(null)
        menu.removeView(bar)
    }

    private fun suspendUpdates() {
        main.removeCallbacks(tick)
        bind(null)
        dragging = false
        dragSession = null
        bar.visibility = View.GONE
    }

    private fun updateSession() {
        if (!active) return
        updateVisibility()
        if (!active) return
        val selected = runCatching { controllerField.get(mediaOwner) as? MediaController }.getOrNull()
        if (selected?.sessionToken != controller?.sessionToken) bind(selected)
        render()
        main.postDelayed(tick, UPDATE_INTERVAL_MS)
    }

    private fun bind(selected: MediaController?) {
        val previousCallback = callback
        if (previousCallback != null) runCatching { controller?.unregisterCallback(previousCallback) }
        callback = null
        controller = selected
        playback = null
        duration = 0L
        if (selected == null) return
        val listener = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (callback !== this) return
                playback = state
                render()
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                if (callback !== this) return
                duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                render()
            }

            override fun onSessionDestroyed() {
                if (callback !== this) return
                duration = 0L
                playback = null
                render()
            }
        }
        callback = listener
        runCatching {
            selected.registerCallback(listener, main)
            duration = selected.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            playback = selected.playbackState
        }.onFailure {
            runCatching { selected.unregisterCallback(listener) }
            callback = null
        }
    }

    private fun render() {
        val state = playback
        val position = state?.timeline()?.positionAt(duration, SystemClock.elapsedRealtime())
        val bottom = if (active && position != null) bottomMargin() else null
        if (position == null || bottom == null) {
            bar.visibility = View.GONE
            return
        }
        val params = bar.layoutParams as FrameLayout.LayoutParams
        if (params.bottomMargin != bottom) {
            params.bottomMargin = bottom
            bar.layoutParams = params
        }
        val seekable = state.actions and PlaybackState.ACTION_SEEK_TO != 0L
        if (bar.isEnabled != seekable) bar.isEnabled = seekable
        if (!dragging) bar.progress = (position.toDouble() / duration * bar.max).roundToInt()
        bar.visibility = View.VISIBLE
    }

    /** Keep the track inside the PiP without covering the stock media controls. */
    private fun bottomMargin(): Int? {
        val height = bar.dp(30)
        val margin = bar.dp(6)
        val bottom = menu.height - margin
        if (!actions.isShown || actions.height == 0) return margin
        actions.getDrawingRect(actionBounds)
        menu.offsetDescendantRectToMyCoords(actions, actionBounds)
        val top = bottom - height
        if (top >= actionBounds.bottom || bottom <= actionBounds.top) return margin
        val aboveActions = actionBounds.top - margin
        return (menu.height - aboveActions).takeIf { aboveActions - height >= bar.dp(46) }
    }

    private fun seek(progress: Int) {
        val target = controller ?: return
        val state = playback ?: return
        if (!active || !allowed() || duration <= 0 ||
            state.actions and PlaybackState.ACTION_SEEK_TO == 0L) return
        // The stock owner may have switched PiP sessions during the gesture.
        val current = runCatching { controllerField.get(mediaOwner) as? MediaController }.getOrNull()
        if (current?.sessionToken != target.sessionToken) return
        runCatching {
            target.transportControls.seekTo((progress.toDouble() / bar.max * duration).toLong())
        }
    }

    private fun PlaybackState.timeline(): PipPlaybackPosition? {
        if (state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_ERROR) return null
        val moving = state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING
        return PipPlaybackPosition(position, lastPositionUpdateTime, playbackSpeed, moving)
    }

    // The view is hosted by SystemUI and uses its native touch and accessibility behavior.
    @SuppressLint("AppCompatCustomView")
    private class PlaybackBar(context: Context) : SeekBar(context) {
        var cancelled = false
            private set
        private var loggedDraw = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

        init {
            visibility = View.GONE
            max = 1000
            background = null
            thumb = null
            thumbOffset = 0
            setPadding(dp(6), 0, dp(6), 0)
            contentDescription = runCatching {
                context.createPackageContext(BuildConfig.APPLICATION_ID, 0)
                    .getText(R.string.glass_pip_playback_position)
            }.getOrDefault("Playback position")
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            val left = paddingLeft.toFloat()
            val right = (width - paddingRight).toFloat()
            if (right <= left) return
            val y = height / 2f + dp(4)
            val adjusting = isEnabled && (isPressed || isFocused)
            val track = dp(if (adjusting) 5 else 3).toFloat()
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            val start = if (rtl) right else left
            val end = if (rtl) left else right
            val position = start + (end - start) * (progress.toFloat() / max)

            paint.color = 0x78051220
            paint.strokeWidth = track + dp(2)
            canvas.drawLine(left, y, right, y, paint)
            paint.color = 0x72e6edf4
            paint.strokeWidth = track
            canvas.drawLine(left, y, right, y, paint)
            paint.color = 0xffedf3f8.toInt()
            if (progress > 0) canvas.drawLine(start, y, position, y, paint)
            if (adjusting) canvas.drawCircle(position, y, dp(4).toFloat(), paint)

            if (!loggedDraw) {
                loggedDraw = true
                HookEntry.log("GLASS_PIP_PROGRESS_DRAWN")
            }
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 250L
        private val menuTypes = setOf("com.android.wm.shell.pip.phone.PipMenuView",
            "com.android.wm.shell.pip2.phone.PipMenuView")

        fun attach(root: View, input: GlassPipSeekInput?, allowed: () -> Boolean): GlassPipProgress? {
            val view = generateSequence(root.parent as? View) { it.parent as? View }
                .firstOrNull { it.javaClass.name in menuTypes } as? ViewGroup ?: return null
            val handler = view.handler ?: return null
            if (handler.looper != Looper.myLooper()) return null
            return runCatching {
                val menu = view.javaClass.compatibleDeclaredField("mMenuContainer").get(view) as? FrameLayout
                    ?: return null
                val actions = view.javaClass.compatibleDeclaredField("mActionsGroup").get(view) as? View
                    ?: return null
                val controller = view.javaClass.compatibleDeclaredField("mController").get(view) ?: return null
                val owner = controller.javaClass.compatibleDeclaredField("mMediaController").get(controller)
                    ?: return null
                val field = owner.javaClass.compatibleDeclaredField("mMediaController")
                if (field.type != MediaController::class.java) return null
                if (generateSequence(actions.parent as? View) { it.parent as? View }.none { it === menu }) return null
                val allowTouches = view.javaClass.compatibleDeclaredField("mAllowTouches")
                val allowTimeout = view.javaClass.compatibleDeclaredField("mAllowMenuTimeout")
                val deferMenuHide = view.javaClass.compatibleDeclaredMethod("repostDelayedHide", Int::class.javaPrimitiveType)
                GlassPipProgress(view, menu, actions, owner, field, handler, allowed, input,
                    allowTouches, allowTimeout, deferMenuHide)
            }.onFailure { HookEntry.log("GLASS_PIP_PROGRESS_UNAVAILABLE ${it.javaClass.simpleName}") }.getOrNull()
        }
    }
}
