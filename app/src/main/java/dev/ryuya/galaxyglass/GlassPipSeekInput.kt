package dev.ryuya.galaxyglass

import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.View
import dev.ryuya.galaxyglass.compat.compatibleDeclaredField
import dev.ryuya.galaxyglass.compat.compatibleDeclaredMethod
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Routes only strokes starting on the seek bar before Shell can classify them as PiP movement. */
internal class GlassPipSeekInput(loader: ClassLoader) {
    private val bars = WeakHashMap<View, WeakReference<GlassPipProgress>>()
    private val strokes = WeakHashMap<Any, Stroke>()

    private class Stroke(val progress: GlassPipProgress, val downTime: Long) {
        var cancelled = false
        var reportedDrag = false
    }

    init {
        for (branch in listOf("pip2", "pip")) {
            runCatching { install(loader, "com.android.wm.shell.$branch.phone") }
                .onFailure { HookEntry.log("GLASS_PIP_SEEK_INPUT_UNAVAILABLE $branch ${it.javaClass.simpleName}") }
        }
    }

    fun register(view: View, bar: GlassPipProgress) {
        synchronized(bars) { bars[view] = WeakReference(bar) }
    }

    fun unregister(view: View) {
        synchronized(bars) { bars.remove(view) }
    }

    private fun install(loader: ClassLoader, packageName: String) {
        val consumer = ModernHook.findClass("$packageName.PipInputConsumer", loader)
        val receiver = ModernHook.findClass("${consumer.name}\$InputEventReceiver", loader)
        val touchHandler = ModernHook.findClass("$packageName.PipTouchHandler", loader)
        val listener = consumer.compatibleDeclaredField("mListener").type
        val capturedHandler = listener.declaredFields.single { it.type == touchHandler }.apply {
            isAccessible = true
        }
        val touchState = touchHandler.compatibleDeclaredField("mTouchState").apply { isAccessible = true }
        val allowInput = touchState.type.compatibleDeclaredField("mAllowInputEvents").apply { isAccessible = true }
        val menuController = touchHandler.compatibleDeclaredField("mMenuController").apply { isAccessible = true }
        val menuView = menuController.type.compatibleDeclaredField("mPipMenuView").apply { isAccessible = true }
        val listenerEvent = listener.compatibleDeclaredMethod("onInputEvent", InputEvent::class.java)
        val receiverEvent = receiver.compatibleDeclaredMethod("onInputEvent", InputEvent::class.java)
        check(listenerEvent.returnType == Void.TYPE)
        ModernHook.deoptimize(receiverEvent)
        ModernHook.aroundMethod(listenerEvent) { chain ->
            val event = chain.args[0] as? MotionEvent ?: return@aroundMethod chain.proceed()
            val key = chain.thisObject ?: return@aroundMethod chain.proceed()
            if (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return@aroundMethod chain.proceed()
            val handled = route(key, event) {
                val handler = capturedHandler.get(key) ?: return@route null
                if (!allowInput.getBoolean(touchState.get(handler))) return@route null
                val view = menuView.get(menuController.get(handler)) as? View ?: return@route null
                synchronized(bars) { bars[view]?.get() }
            }
            // The receiver still acknowledges this input event through its stock finally block.
            if (handled) null else chain.proceed()
        }
        HookEntry.log("GLASS_PIP_SEEK_INPUT_READY $packageName")
    }

    private fun route(key: Any, event: MotionEvent, currentBar: () -> GlassPipProgress?): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            synchronized(strokes) { strokes.remove(key) }
            val progress = currentBar() ?: return false
            if (!progress.beginSeekGesture(event)) return false
            synchronized(strokes) { strokes[key] = Stroke(progress, event.downTime) }
            return true
        }
        val stroke = synchronized(strokes) { strokes[key] } ?: return false
        if (stroke.downTime != event.downTime) return false
        val finished = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        if (finished) synchronized(strokes) { strokes.remove(key) }
        if (!stroke.cancelled) {
            val cancel = action == MotionEvent.ACTION_POINTER_DOWN || !stroke.progress.acceptsSeekGesture()
            val delivered = stroke.progress.deliverSeekGesture(event, cancel)
            if (!cancel && delivered && action == MotionEvent.ACTION_MOVE && !stroke.reportedDrag) {
                stroke.reportedDrag = true
                HookEntry.log("GLASS_PIP_SEEK_DRAG_ROUTED")
            }
            stroke.cancelled = cancel
        }
        return true
    }
}
