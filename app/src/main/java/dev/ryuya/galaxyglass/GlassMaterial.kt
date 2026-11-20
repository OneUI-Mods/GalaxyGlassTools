package dev.ryuya.galaxyglass

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.Log
import kotlin.math.min

internal data class GlassAppearance(
    val quick: Boolean = false,
    val notifications: Boolean = false,
    val home: Boolean = false,
    val strength: Int = 60,
    val tint: Int = DEFAULT_TINT,
    val volume: Boolean = false,
    val volumeTheme: Int = 0,
    val systemSurfaces: Boolean = false,
    val lockScreen: Boolean = false,
) {
    companion object {
        const val QUICK = "glass_quick"
        const val NOTIFICATIONS = "glass_notifications"
        const val VOLUME = "glass_volume"
        const val HOME = "glass_home"
        const val SYSTEM_SURFACES = "glass_system_surfaces"
        const val LOCK_SCREEN = "glass_lock_screen"
        const val STRENGTH = "glass_strength"
        const val TINT = "glass_tint"
        const val DEFAULT_STRENGTH = 60
        const val DEFAULT_TINT = 50

        // UIKit rounds its normalized value to the nearest twentieth.
        fun tintPercent(value: Int): Int = ((value.coerceIn(0, 100) + 2) / 5) * 5

        fun lockScreenEnabled(prefs: SharedPreferences) =
            prefs.getBoolean(LOCK_SCREEN, prefs.getBoolean(SYSTEM_SURFACES, false))
    }
}

/** Rim and surface lighting shared by the preview and the three host adapters. */
internal class GlassMaterial(private val density: Float) {
    enum class Profile {
        STANDARD, QUICK_CONTROL, QUICK_GROUP, QUICK_SLIDER, NOTIFICATION, VOLUME, SYSTEM_SURFACE, POWER_ACTION,
        APP_CHROME,
    }

    var tint: Int = GlassAppearance.DEFAULT_TINT
    val tintFactor: Float
        get() = tintFactor(tint)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val reflectionTransform = Matrix()
    private val reflection = RadialGradient(
        0f, 0f, 1f, intArrayOf(0xc9ffffff.toInt(), 0x5fd9efff, 0x00ffffff),
        floatArrayOf(0f, 0.43f, 1f), Shader.TileMode.CLAMP,
    )
    private val outline = Path()
    private val corners = FloatArray(8)
    private val contourTransform = Matrix()
    private val surfaceLighting by lazy {
        if (Build.VERSION.SDK_INT < 33) null else runCatching { GlassSurfaceLighting(density) }
            .onFailure { Log.w("GalaxyGlassTools", "Glass bevel unavailable", it) }.getOrNull()
    }
    private data class Finish(val body: Shader, val edge: Shader)
    private val gradients = object : LinkedHashMap<Lighting, Finish>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Lighting, Finish>?) = size > 16
    }

    private data class Lighting(
        val height: Int,
        val width: Int,
        val strength: Int,
        val dark: Boolean,
        val tint: Int,
        val profile: Profile,
    )

    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        topRadius: Float,
        bottomRadius: Float,
        strength: Int,
        dark: Boolean,
        fill: Boolean = true,
        glint: GlassGlint? = null,
        contour: Path? = null,
        profile: Profile = Profile.STANDARD,
    ) {
        val radiusLimit = min(right - left, bottom - top) * 0.5f
        if (radiusLimit <= 0f) return
        for (index in corners.indices) corners[index] = if (index < 4) topRadius else bottomRadius
        drawSurface(canvas, left, top, right, bottom, corners, strength, dark,
            fill, rim = true, glint = glint, contour = contour, profile = profile)
    }

    fun drawSurface(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radii: FloatArray,
        strength: Int,
        dark: Boolean,
        fill: Boolean,
        rim: Boolean,
        glint: GlassGlint? = null,
        opacity: Float = 1f,
        profile: Profile = Profile.STANDARD,
        contour: Path? = null,
    ): Boolean {
        val width = right - left
        val height = bottom - top
        if (width < 6f * density || height < 6f * density || strength <= 0 || opacity <= 0f ||
            canvas.quickReject(left, top, right, bottom)) return false
        val lighting = Lighting(height.toInt(), width.toInt(), strength.coerceIn(0, 100), dark, tint, profile)
        val finish = gradients.getOrPut(lighting) { lighting.build() }
        // A single line follows the native corner curve just inside the clip.
        val lineWidth = (0.54f + strength * 0.0032f) * density
        val inset = lineWidth * 0.64f
        if (contour != null) {
            // The caller supplies the native outline in unit coordinates.
            contourTransform.setScale(width - 2f * inset, height - 2f * inset)
            contourTransform.postTranslate(inset, inset)
            outline.set(contour)
            outline.transform(contourTransform)
        } else {
            val radiusLimit = min(width, height) * 0.5f
            for (index in corners.indices) {
                corners[index] = (radii[index].coerceIn(0f, radiusLimit) - inset).coerceAtLeast(0f)
            }
            rect.set(inset, inset, width - inset, height - inset)
            outline.rewind()
            outline.addRoundRect(rect, corners, Path.Direction.CW)
        }
        val save = canvas.save()
        try {
            canvas.translate(left, top)
            if (canvas.quickReject(outline)) return false
            paint.alpha = (255 * opacity).toInt().coerceIn(0, 255)
            if (fill) {
                paint.style = Paint.Style.FILL
                paint.shader = finish.body
                canvas.drawPath(outline, paint)
            }
            if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated &&
                (profile == Profile.SYSTEM_SURFACE || profile == Profile.POWER_ACTION)) {
                surfaceLighting?.draw(canvas, outline, width, height, radii, strength, dark,
                    icon = profile == Profile.POWER_ACTION, opacity = opacity)
            }
            if (rim) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = lineWidth
                paint.shader = finish.edge
                canvas.drawPath(outline, paint)
                if (glint != null && glint.amount > 0f) {
                    val x = width * (0.08f + 0.84f * glint.x)
                    val y = if (glint.y < 0.60f) 0f else height
                    reflectionTransform.setScale(width * 0.39f, height * 0.65f)
                    reflectionTransform.postTranslate(x, y)
                    reflection.setLocalMatrix(reflectionTransform)
                    paint.shader = reflection
                    paint.alpha = (opacity * glint.amount * (44 + strength * 0.66f)).toInt().coerceIn(0, 255)
                    canvas.drawPath(outline, paint)
                }
            }
        } finally {
            paint.alpha = 255
            canvas.restoreToCount(save)
        }
        return true
    }

    private fun Lighting.build(): Finish {
        fun color(rgb: Int, alpha: Int): Int = (alpha * strength / 100 shl 24) or rgb
        fun fillTint(rgb: Int, alpha: Int): Int = color(rgb, (alpha * tintFactor(tint)).toInt().coerceIn(0, 255))
        val white = if (profile == Profile.STANDARD) 0xf5f8ff else 0xf8f9fb
        val shade = 0x111a29
        val bodyColors: IntArray
        val edgeColors: IntArray
        when (profile) {
            Profile.APP_CHROME -> {
                // Flush bars fade into the surrounding content instead of lighting up all four edges.
                val tone = if (dark) 0x667b91 else 0x8ca6bc
                val horizontal = width >= height
                val wash = LinearGradient(0f, 0f,
                    if (horizontal) 0f else width.toFloat(), if (horizontal) height.toFloat() else 0f,
                    intArrayOf(tone, fillTint(tone, if (dark) 50 else 43), tone),
                    floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP)
                return Finish(wash, wash)
            }
            Profile.STANDARD -> {
                bodyColors = if (dark) {
                    intArrayOf(color(white, 20), fillTint(0x202633, 54), fillTint(0x141c28, 71))
                } else {
                    intArrayOf(fillTint(white, 72), fillTint(0xe7eff6, 46), fillTint(0xe2eaf2, 30))
                }
                edgeColors = intArrayOf(color(white, 204), color(white, 101),
                    color(shade, if (dark) 76 else 55), color(white, if (dark) 86 else 119))
            }
            Profile.QUICK_CONTROL -> {
                val tintColor = if (dark) 0x1d2026 else 0xf5f6f9
                bodyColors = intArrayOf(color(white, 23), fillTint(tintColor, 45), fillTint(tintColor, 58))
                edgeColors = intArrayOf(color(white, 198), color(white, 91), color(shade, 108), color(white, 113))
            }
            Profile.VOLUME -> {
                val tintColor = if (dark) 0x222934 else 0xf4f8fc
                bodyColors = intArrayOf(color(white, 31), fillTint(tintColor, 49), fillTint(tintColor, 67))
                edgeColors = intArrayOf(color(white, 212), color(white, 96), color(shade, 108), color(white, 128))
            }
            Profile.SYSTEM_SURFACE, Profile.POWER_ACTION -> {
                bodyColors = if (dark) {
                    intArrayOf(color(white, 27), fillTint(0x1c2430, 53), fillTint(0x101924, 68))
                } else {
                    intArrayOf(fillTint(white, 75), fillTint(0xe3edf4, 39), fillTint(0xcddce9, 52))
                }
                // Leave room for the curved inner highlight to illuminate this narrow outer rim.
                edgeColors = intArrayOf(color(white, 157), color(white, 68),
                    color(shade, if (dark) 94 else 82), color(0xc2dcf3, 107))
            }
            Profile.QUICK_GROUP, Profile.QUICK_SLIDER, Profile.NOTIFICATION -> {
                val tintColor = if (dark) 0x23262d else 0xf5f6f9
                val tintAlpha = when (profile) {
                    Profile.QUICK_GROUP -> 27
                    Profile.QUICK_SLIDER -> 36
                    else -> 43
                }
                bodyColors = intArrayOf(color(white, 17), fillTint(tintColor, tintAlpha), fillTint(tintColor, tintAlpha + 7))
                val highlight = if (profile == Profile.QUICK_SLIDER) 161 else 131
                edgeColors = intArrayOf(color(white, highlight), color(white, 57), color(shade, 99), color(white, 66))
            }
        }
        val body = LinearGradient(
            0f, 0f, width * 0.52f, height.toFloat(),
            bodyColors,
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP,
        )
        val edge = LinearGradient(
            0f, 0f, width * 0.60f, height.toFloat(),
            edgeColors,
            floatArrayOf(0f, 0.30f, 0.67f, 1f), Shader.TileMode.CLAMP,
        )
        return Finish(body, edge)
    }

    private fun tintFactor(value: Int): Float = 0.35f + 1.3f * value / 100f
}
