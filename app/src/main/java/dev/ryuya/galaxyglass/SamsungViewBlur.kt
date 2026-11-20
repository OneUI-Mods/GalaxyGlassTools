package dev.ryuya.galaxyglass

import android.view.View

internal class SamsungViewBlur {
    private val info = Class.forName("android.view.SemBlurInfo")
    private val builder = Class.forName("android.view.SemBlurInfo\$Builder")
    private val constructor = builder.getConstructor(Int::class.javaPrimitiveType)
    private val radius = builder.getMethod("setRadius", Int::class.javaPrimitiveType)
    private val corner = builder.getMethod("setBackgroundCornerRadius", Float::class.javaPrimitiveType)
    private val tint = builder.getMethod("setBackgroundColor", Int::class.javaPrimitiveType)
    private val build = builder.getMethod("build")
    private val set = View::class.java.getMethod("semSetBlurInfo", info)
    private val installed = View::class.java.getDeclaredField("mBlurInfo").apply { isAccessible = true }
    private val mode = info.getField("BLUR_MODE_WINDOW").getInt(null)

    fun current(view: View): Any? = installed.get(view)

    fun apply(view: View, pixels: Int, cornerPixels: Float, color: Int): Any? {
        val config = constructor.newInstance(mode)
        radius.invoke(config, pixels)
        corner.invoke(config, cornerPixels)
        tint.invoke(config, color)
        val requested = build.invoke(config)
        set.invoke(view, requested)
        return current(view).takeIf { it === requested }
    }

    fun clear(view: View) {
        set.invoke(view, null)
    }
}
