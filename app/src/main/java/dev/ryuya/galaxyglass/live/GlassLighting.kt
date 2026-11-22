package dev.ryuya.galaxyglass.live

import android.graphics.LinearGradient
import android.graphics.Shader
import dev.ryuya.galaxyglass.GlassGradients
import dev.ryuya.galaxyglass.GlassLightingV1
import dev.ryuya.galaxyglass.GlassMaterial.Profile

internal class GlassLighting : GlassLightingV1 {
    override val apiVersion: Int get() = 1

    override fun gradients(
        width: Int, height: Int, strength: Int, dark: Boolean, tint: Int, profile: Profile,
    ): GlassGradients {
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
                return GlassGradients(wash, wash)
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
            Profile.PRIVACY_PANEL -> {
                bodyColors = if (dark) {
                    intArrayOf(fillTint(0x4d5158, 48), fillTint(0x41454c, 44), fillTint(0x383c43, 42))
                } else {
                    intArrayOf(fillTint(white, 80), fillTint(0xe6edf4, 53), fillTint(0xdbe4ed, 48))
                }
                edgeColors = intArrayOf(color(white, 111), color(white, 58),
                    color(shade, if (dark) 30 else 48), color(white, 56))
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
        return GlassGradients(body, edge)
    }

    private fun tintFactor(value: Int): Float = 0.35f + 1.3f * value / 100f
}
