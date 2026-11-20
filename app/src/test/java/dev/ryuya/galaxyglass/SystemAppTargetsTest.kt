package dev.ryuya.galaxyglass

import android.content.pm.ApplicationInfo.FLAG_HAS_CODE
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAppTargetsTest {
    @Test
    fun acceptsSystemAndUpdatedSystemAppsWithCode() {
        assertTrue(SystemAppTargets.includes("com.android.settings", FLAG_SYSTEM or FLAG_HAS_CODE, true))
        assertTrue(SystemAppTargets.includes("com.sec.android.app.myfiles", FLAG_UPDATED_SYSTEM_APP or FLAG_HAS_CODE, true))
    }

    @Test
    fun keepsThirdPartyDisabledResourceOnlyAndExistingHostsOutOfTheCommonAdapter() {
        assertFalse(SystemAppTargets.includes("example.browser", FLAG_HAS_CODE, true))
        assertFalse(SystemAppTargets.includes("com.android.settings", FLAG_SYSTEM or FLAG_HAS_CODE, false))
        assertFalse(SystemAppTargets.includes("com.android.overlay", FLAG_SYSTEM, true))
        for (name in GlassTargets.HOSTS + "android") {
            assertFalse(SystemAppTargets.includes(name, FLAG_SYSTEM or FLAG_HAS_CODE, true))
        }
        assertFalse(SystemAppTargets.hasUiActivity("android.app.AppDetailsActivity"))
    }
}
