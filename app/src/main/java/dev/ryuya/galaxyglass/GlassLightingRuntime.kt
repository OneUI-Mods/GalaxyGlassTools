package dev.ryuya.galaxyglass

import android.content.Context
import android.graphics.Shader
import android.util.Log
import dev.ryuya.galaxyglass.live.GlassLighting

internal data class GlassGradients(val body: Shader, val edge: Shader)

internal interface GlassLightingV1 {
    val apiVersion: Int
    fun gradients(width: Int, height: Int, strength: Int, dark: Boolean, tint: Int,
                  profile: GlassMaterial.Profile): GlassGradients
}

internal object GlassLightingRuntime {
    private val bundled = GlassLighting()
    private val api = SignedLogic(
        BuildConfig.APPLICATION_ID,
        BuildConfig.VERSION_CODE.toLong(),
        "e19cfbca86a4988919d8eb58a9655518a126f3d2d96f03cf96285438364bad7e",
        GlassLightingV1::class.java,
        "dev.ryuya.galaxyglass.live.GlassLighting",
        bundled,
        accepts = { it.apiVersion == 1 },
        report = { message -> Log.i("GalaxyGlassTools", message) },
    )

    fun start(host: Context) = api.start(host)
    fun capture() = api.current

    fun gradients(code: SignedLogic.Snapshot<GlassLightingV1>, width: Int, height: Int, strength: Int, dark: Boolean,
                  tint: Int, profile: GlassMaterial.Profile): GlassGradients {
        try {
            return code.logic.gradients(width, height, strength, dark, tint, profile)
        } catch (error: RuntimeException) {
            if (code.logic === bundled) throw error
            api.reject(code, error)
        } catch (error: LinkageError) {
            if (code.logic === bundled) throw error
            api.reject(code, error)
        }
        return bundled.gradients(width, height, strength, dark, tint, profile)
    }
}
