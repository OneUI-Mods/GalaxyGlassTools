package dev.ryuya.galaxyglass

import android.content.pm.ApplicationInfo.FLAG_HAS_CODE
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationGlassTargetsTest {
    @Test
    fun acceptsSystemAndUpdatedSystemAppsWithCode() {
        assertTrue(ApplicationGlassTargets.includes("com.android.settings", FLAG_SYSTEM or FLAG_HAS_CODE, true))
        assertTrue(ApplicationGlassTargets.includes("com.sec.android.app.myfiles", FLAG_UPDATED_SYSTEM_APP or FLAG_HAS_CODE, true))
    }

    @Test
    fun keepsThirdPartyDisabledResourceOnlyAndDedicatedHostsOutOfTheCommonAdapter() {
        assertFalse(ApplicationGlassTargets.includes("example.browser", FLAG_HAS_CODE, true))
        assertFalse(ApplicationGlassTargets.includes("com.android.settings", FLAG_SYSTEM or FLAG_HAS_CODE, false))
        assertFalse(ApplicationGlassTargets.includes("com.android.overlay", FLAG_SYSTEM, true))
        for (name in GlassTargets.HOSTS + setOf("android", BuildConfig.APPLICATION_ID)) {
            assertFalse(ApplicationGlassTargets.includes(name, FLAG_SYSTEM or FLAG_HAS_CODE, true))
        }
        assertFalse(ApplicationGlassTargets.hasUiActivity("android.app.AppDetailsActivity"))
    }

    @Test
    fun keepsInputMethodAndCustomToolbarAnchorsWithinTheirSystemPackages() {
        val samsung = "com.samsung.android.honeyboard"
        assertTrue(ApplicationGlassTargets.hasInputMethodSurfaces(samsung))
        assertTrue("keyboard_background" in ApplicationGlassTargets.inputMethodPlates(samsung))
        assertTrue("clipboardPanel" in ApplicationGlassTargets.inputMethodPlates(samsung))
        assertFalse(ApplicationGlassTargets.hasInputMethodSurfaces("example.keyboard"))
        val toolbar = "org.chromium.chrome.browser.toolbar.top.ToolbarPhone"
        assertTrue(ApplicationGlassTargets.usesOverlay("com.android.chrome", toolbar))
        assertFalse(ApplicationGlassTargets.usesOverlay("example.browser", toolbar))
        assertTrue("sem_more_apps_grid_view" in ApplicationGlassTargets.plates("com.android.intentresolver"))
    }

    @Test
    fun matchesResolverTabsByLayoutAndKeepsTheAnchorPackageSpecific() {
        val tab = "resolver_profile_tab_button"
        for (resolver in listOf("com.android.intentresolver", "com.google.android.intentresolver")) {
            assertTrue(tab in ApplicationGlassTargets.layoutPlates(resolver))
            assertFalse(tab in ApplicationGlassTargets.plates(resolver))
        }
        assertFalse(tab in ApplicationGlassTargets.layoutPlates("com.android.settings"))
    }
}
