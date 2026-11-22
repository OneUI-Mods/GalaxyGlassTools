package dev.ryuya.galaxyglass

import dev.ryuya.galaxyglass.resident.ComposeDrawCallback
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ComposeDrawCallbackTest {
    interface Draw {
        fun paint(canvas: NativeCanvas): Any
    }

    class NativeCanvas {
        var contentCalls = 0
        fun drawContent() {
            contentCalls++
        }
    }

    @Test
    fun existingProxyDrawsNativeContentDuringHandoffAndThenUsesNewImplementation() {
        val canvas = NativeCanvas()
        val nativeDraw = NativeCanvas::class.java.getMethod("drawContent")
        val result = Any()
        val labels = mutableListOf<String>()
        fun render(label: String) = InvocationHandler { _, _, args ->
            labels.add(label)
            nativeDraw.invoke(args[0])
            result
        }
        val delegate = AtomicReference<InvocationHandler?>(render("older"))
        val callback = ComposeDrawCallback(arrayOf(arrayOf("overlay"), delegate), nativeDraw, "paint", result)
        val viewDraw = Proxy.newProxyInstance(Draw::class.java.classLoader, arrayOf(Draw::class.java), callback) as Draw

        assertSame(result, viewDraw.paint(canvas))
        delegate.set(null)
        assertSame(result, viewDraw.paint(canvas))
        delegate.set(render("newer"))
        assertSame(result, viewDraw.paint(canvas))

        assertEquals(listOf("older", "newer"), labels)
        assertEquals(3, canvas.contentCalls)
        assertEquals(viewDraw, viewDraw)
    }
}
