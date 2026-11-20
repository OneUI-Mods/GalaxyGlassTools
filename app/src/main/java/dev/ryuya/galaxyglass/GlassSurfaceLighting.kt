package dev.ryuya.galaxyglass

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.util.Log
import androidx.annotation.RequiresApi

/** Lights the glass bevel inside its existing outline without sampling content or starting capture. */
@RequiresApi(33)
internal class GlassSurfaceLighting(density: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shader = RuntimeShader(SOURCE)
    private var reported = false
    private var styleStrength = -1
    private var styleDark: Boolean? = null
    private var styleIcon: Boolean? = null

    init {
        shader.setFloatUniform("pixelsPerDp", density)
    }

    fun draw(
        canvas: Canvas,
        contour: Path,
        width: Float,
        height: Float,
        corners: FloatArray,
        strength: Int,
        dark: Boolean,
        icon: Boolean,
        opacity: Float,
    ) {
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("corners", corners[0], corners[2], corners[4], corners[6])
        if (styleStrength != strength) {
            shader.setFloatUniform("strength", strength / 100f)
            styleStrength = strength
        }
        if (styleDark != dark) {
            shader.setFloatUniform("dark", if (dark) 1f else 0f)
            styleDark = dark
        }
        if (styleIcon != icon) {
            shader.setFloatUniform("icon", if (icon) 1f else 0f)
            styleIcon = icon
        }
        paint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        paint.shader = shader
        canvas.drawPath(contour, paint)
        if (!reported) {
            reported = true
            Log.i("GalaxyGlassTools", "GLASS_SYSTEM_BEVEL_DRAWN")
        }
    }

    private companion object {
        const val SOURCE = """
            uniform float2 size;
            uniform float4 corners;
            uniform float pixelsPerDp;
            uniform float strength;
            uniform float dark;
            uniform float icon;

            $GLASS_CIRCULAR_BEVEL

            half4 main(float2 position) {
                float2 halfSize = size * 0.5;
                float2 local = position - halfSize;
                float limit = min(halfSize.x, halfSize.y);
                float radius = local.y < 0.0
                    ? (local.x < 0.0 ? corners.x : corners.y)
                    : (local.x < 0.0 ? corners.w : corners.z);
                float2 q = abs(local) - halfSize + clamp(radius, 0.0, limit);
                float2 outward = max(q, 0.0);
                float distanceToEdge = length(outward) + min(max(q.x, q.y), 0.0)
                    - clamp(radius, 0.0, limit);
                if (dot(outward, outward) < 0.0001) {
                    outward = q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
                }
                float2 normal = normalize(outward) * sign(local);
                float depth = max(0.0, -distanceToEdge);
                float bevelWidth = min(mix(13.0, 8.0, icon) * pixelsPerDp, limit * 0.53);
                float edge = circularBevel(depth, bevelWidth);
                float shoulder = smoothstep(0.0, bevelWidth * 0.3, depth)
                    * (1.0 - smoothstep(bevelWidth * 0.3, bevelWidth, depth));
                float light = dot(normal, float2(-0.53, -0.848));
                float primary = max(0.0, light);
                float reflected = max(0.0, -light);
                float2 uv = position / size;
                float gloss = 1.0 - smoothstep(0.06, 0.63,
                    length((uv - float2(0.26, 0.07)) * float2(0.78, 1.25)));

                float shine = (edge * (0.15 + 0.45 * primary * primary)
                    + shoulder * primary * 0.13 + gloss * mix(0.092, 0.046, icon))
                    * mix(0.8, 1.0, dark);
                float bounce = edge * reflected * reflected * mix(0.12, 0.17, dark);
                float shadow = shoulder * (0.045 + reflected * 0.22) * mix(1.0, 0.78, dark);
                float3 color = float3(0.92, 0.975, 1.0) * shine
                    + float3(0.70, 0.89, 1.0) * bounce + float3(0.06, 0.095, 0.15) * shadow;
                return half4(color * strength, (shine + bounce + shadow) * strength);
            }
        """
    }
}
