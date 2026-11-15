package app.gamenative.ui.screen.wow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WowVulkanDiagnosticsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `Mali policy removes stale Turnip and spoofing options and preserves unrelated settings`() {
        val dir = WowVulkanDiagnostics.directory(context)
        File(dir, "system-vulkan.txt").writeText("GPU[0]=Mali-G615; apiVersion=1.3.280\nRESULT: PASS: test fixture")
        val env = EnvVars("ADRENOTOOLS_DRIVER_PATH=/turnip ADRENOTOOLS_HOOKS_PATH=/hooks ADRENOTOOLS_DRIVER_NAME=turnip.so TU_DEBUG=noconform FD_DEV_FEATURES=test WRAPPER_DEVICE_NAME=Fake WRAPPER_VENDOR_ID=1 WRAPPER_DEVICE_ID=2 WRAPPER_EXTENSION_BLACKLIST=VK_KHR_swapchain DXVK_LOG_LEVEL=none WINEDEBUG=-all PULSE_LATENCY_MSEC=144")
        val imageFs = ImageFs.find(tmp.root)
        env.put("ENABLE_UTIL_LAYER", "1")
        WowVulkanDiagnostics.applySystemEnvironment(context, env, imageFs)
        assertFalse(env.any { it.startsWith("ADRENOTOOLS_") || it.startsWith("TU_") || it.startsWith("FD_") })
        listOf("WRAPPER_DEVICE_NAME", "WRAPPER_VENDOR_ID", "WRAPPER_DEVICE_ID", "WRAPPER_EXTENSION_BLACKLIST").forEach { assertFalse(env.has(it)) }
        assertEquals("1.3.280", env.get("WRAPPER_VK_VERSION"))
        assertEquals("info", env.get("DXVK_LOG_LEVEL"))
        assertEquals("1", env.get("WRAPPER_NO_PATCH_OPCONSTCOMP"))
        assertEquals("info,error,validation", env.get("WRAPPER_LOG_LEVEL"))
        assertTrue(env.get("WINEDEBUG").contains("warn+vulkan"))
        assertFalse(env.get("WINEDEBUG").contains("+seh"))
        assertFalse(env.has("ENABLE_UTIL_LAYER"))
        assertEquals("144", env.get("PULSE_LATENCY_MSEC"))
        assertEquals(File(imageFs.libDir, "libvulkan_wrapper.so").absolutePath, env.get("WOW_VULKAN_WRAPPER"))
        val icd = JSONObject(File(env.get("VK_ICD_FILENAMES")).readText()).getJSONObject("ICD")
        assertEquals("1.3.280", icd.getString("api_version"))
        assertEquals("libwow_vulkan.so", File(icd.getString("library_path")).name)
        assertEquals(env.get("VK_ICD_FILENAMES"), env.get("VK_DRIVER_FILES"))
        assertEquals("L:\\", env.get("DXVK_LOG_PATH"))
        File(dir, "system-vulkan.txt").writeText("GPU[0]=Mali-G615; apiVersion=1.4.300\nRESULT: PASS: test fixture")
        WowVulkanDiagnostics.applySystemEnvironment(context, env, imageFs)
        assertEquals("1.3.289", env.get("WRAPPER_VK_VERSION"))
    }

    @Test fun `failure classification keeps the VkResult and does not report successful device creation`() {
        assertNull(WowVulkanDiagnostics.failureFromLines(listOf("Wine/DXVK wrapper vkCreateDevice=VK_SUCCESS (0)", "info: Device ready")))
        val error = "Wine/DXVK wrapper vkCreateDevice=VK_ERROR_FEATURE_NOT_PRESENT (-8)"
        assertEquals(error, WowVulkanDiagnostics.failureFromLines(listOf("GPU=Mali", error)))
        assertEquals(error, WowVulkanDiagnostics.failureFromLines(listOf(error, "D3D11: Failed to create D3D11 device")))
        assertNull(WowVulkanDiagnostics.failureFromLines(listOf(error, "Wine/DXVK wrapper vkCreateDevice=VK_SUCCESS (0)")))
        val extension = "err: Required Vulkan extension VK_EXT_robustness2 not supported"
        assertEquals(extension, WowVulkanDiagnostics.failureFromLines(listOf(extension, "DxvkAdapter: Failed to create device")))
        assertEquals("err: D3D11CreateDevice: Failed to create D3D11 device", WowVulkanDiagnostics.failureFromLines(listOf("err: D3D11CreateDevice: Failed to create D3D11 device")))
    }
}
