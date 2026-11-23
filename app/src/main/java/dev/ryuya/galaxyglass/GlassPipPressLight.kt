package dev.ryuya.galaxyglass

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi

/** Draws the light spreading across a pressed PiP button and around its curved edge. */
@RequiresApi(33)
internal class GlassPipPressLight {
    private val light = RuntimeShader(SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = light }

    fun draw(canvas: Canvas, bounds: Rect, x: Float, y: Float, press: Float, dark: Boolean) {
        light.setFloatUniform("area", bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.width().toFloat(), bounds.height().toFloat())
        light.setFloatUniform("touch", x, y)
        light.setFloatUniform("press", press)
        light.setFloatUniform("dark", if (dark) 1f else 0f)
        canvas.drawOval(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
            bounds.bottom.toFloat(), paint)
    }

    private companion object {
        const val SOURCE = """
            uniform float4 area;
            uniform float2 touch;
            uniform float press;
            uniform float dark;

            half4 main(float2 pixel) {
                float2 point = ((pixel - area.xy) / area.zw - 0.5) * 2.0;
                float2 origin = (touch - 0.5) * 1.55;
                float radius = length(point);
                float spread = mix(0.50, 1.60, press);
                float illumination = 1.0 - smoothstep(0.0, spread, length(point - origin));
                float interior = (0.11 + 0.28 * illumination) * press;

                // Several directions still catch some light when the finger is near the center.
                float2 lightOffset = origin + float2(0.02, -0.32);
                float2 direction = lightOffset / max(length(lightOffset), 0.001);
                float facing = clamp(0.5 + 0.5 * dot(point / max(radius, 0.001), direction), 0.0, 1.0);
                float reflected = smoothstep(0.79, 0.97, radius) * pow(facing, 4.0) * 0.24 * press;
                float alpha = (interior + reflected) * mix(0.83, 1.0, dark);
                half3 color = mix(half3(1.0, 0.99, 0.96), half3(0.93, 0.97, 1.0), half(dark));
                return half4(color * half(alpha), half(alpha));
            }
        """
    }
}
