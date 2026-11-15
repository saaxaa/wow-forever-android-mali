package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Test

class WowGraphicsBackendTest {
    @Test fun `Xiaomi 14T selects system Vulkan even with a Qualcomm-like hardware token`() {
        assertEquals(WowGraphicsBackend.MALI_SYSTEM, WowGraphicsBackend.detect("Mali-G615 MC6", "Dimensity 8300-Ultra", "sun"))
    }

    @Test fun `Mali detection ignores case and supports newer Arm names`() {
        listOf("mali-g615", "ARM Mali-G715", "Immortalis-G720").forEach {
            assertEquals(WowGraphicsBackend.MALI_SYSTEM, WowGraphicsBackend.detect(it, "", ""))
        }
    }

    @Test fun `Adreno 740 and 8xx retain Turnip on all manufacturers`() {
        listOf("Adreno (TM) 740", "Adreno (TM) 830", "Adreno 840", "Adreno A12").forEach {
            assertEquals(WowGraphicsBackend.TURNIP, WowGraphicsBackend.detect(it, "", ""))
        }
    }

    @Test fun `MediaTek fallback is limited to unknown renderers`() {
        assertEquals(WowGraphicsBackend.MALI_SYSTEM, WowGraphicsBackend.detect("", "", "mt6897"))
        assertEquals(WowGraphicsBackend.MALI_SYSTEM, WowGraphicsBackend.detect("", "MT6897", ""))
        assertEquals(WowGraphicsBackend.MALI_SYSTEM, WowGraphicsBackend.detect("unknown", "MediaTek Dimensity 8300", ""))
        assertEquals(WowGraphicsBackend.UNSUPPORTED, WowGraphicsBackend.detect("PowerVR", "MediaTek", "mt9999"))
        assertEquals(WowGraphicsBackend.UNSUPPORTED, WowGraphicsBackend.detect("", "", "mtfoo"))
    }

    @Test fun `known Snapdragon fallback still selects Turnip when EGL is unavailable`() {
        assertEquals(WowGraphicsBackend.TURNIP, WowGraphicsBackend.detect("", "SM8750", ""))
        assertEquals(WowGraphicsBackend.TURNIP, WowGraphicsBackend.detect("unknown", "", "sun"))
    }

    @Test fun `unknown GPUs do not silently select Turnip`() {
        assertEquals(WowGraphicsBackend.UNSUPPORTED, WowGraphicsBackend.detect("", "", ""))
        assertEquals(WowGraphicsBackend.UNSUPPORTED, WowGraphicsBackend.detect("Xclipse", "Exynos", ""))
    }
}
