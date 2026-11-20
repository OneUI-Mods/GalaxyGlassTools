package dev.ryuya.galaxyglass

/** Highest verified rendering stage for a native surface during this boot. */
internal enum class GlassSurfaceStage(val label: Int) {
    FOUND(R.string.glass_surface_found),
    DRAWN(R.string.glass_surface_drawn),
    NATIVE_BLUR(R.string.glass_surface_native_blur),
    REFRACTED(R.string.glass_surface_refracted);

    companion object {
        const val FEATURE_PREFIX = "glass_surface_"
        const val SYSTEM_STATUS_PREFIX = "com.android.systemui." + FEATURE_PREFIX
        const val HOME_STATUS_PREFIX = GlassTargets.LAUNCHER + "." + FEATURE_PREFIX
    }
}
