package dev.ryuya.galaxyglass

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi

/** Owned by one shader binding. Preserve all four float values, including the sign of zero. */
@RequiresApi(33)
internal class GlassUniform4 {
    private var initialized = false
    private var a = 0
    private var b = 0
    private var c = 0
    private var d = 0

    fun set(shader: RuntimeShader, name: String, x: Float, y: Float, z: Float, w: Float) {
        val xBits = x.toRawBits()
        val yBits = y.toRawBits()
        val zBits = z.toRawBits()
        val wBits = w.toRawBits()
        if (initialized && a == xBits && b == yBits && c == zBits && d == wBits) return
        shader.setFloatUniform(name, x, y, z, w)
        a = xBits
        b = yBits
        c = zBits
        d = wBits
        initialized = true
    }
}
