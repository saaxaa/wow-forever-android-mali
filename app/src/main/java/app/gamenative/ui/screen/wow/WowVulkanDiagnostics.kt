package app.gamenative.ui.screen.wow

import android.content.Context
import android.os.Build
import android.util.Log
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import java.io.File

/** Logs contain device/driver information. No Battle.net credentials are recorded by this class. */
object WowVulkanDiagnostics {
    @JvmStatic external fun probeSystem(): String

    fun directory(context: Context): File = File(context.filesDir, "graphics_logs").apply { mkdirs() }

    fun prepare(context: Context, renderer: String): String {
        System.loadLibrary("wow_vulkan")
        val dir = directory(context)
        val report = "Device=${Build.MANUFACTURER} ${Build.MODEL}; hardware=${Build.HARDWARE}; " +
            "SoC=${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown"}; EGL=$renderer\n" + probeSystem()
        File(dir, "system-vulkan.txt").writeText(report)
        check(report.contains("RESULT: PASS:")) {
            (report.lineSequence().firstOrNull { it.startsWith("RESULT: FAIL:") } ?: "System Vulkan probe did not complete") +
                "\nReport: ${File(dir, "system-vulkan.txt")}"
        }
        return report.lineSequence().first { it.startsWith("GPU[0]=") }.substringAfter("apiVersion=")
    }

    /** Called after DXVKHelper and all generic wrapper options, so defaults cannot override it. */
    @JvmStatic
    fun applySystemEnvironment(context: Context, env: EnvVars, imageFs: ImageFs) {
        val dir = directory(context)
        val systemApi = File(dir, "system-vulkan.txt").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("GPU[0]=") }?.substringAfter("apiVersion=")
            ?: error("System Vulkan preflight report is missing; return to the launcher and press Play")
        // The pinned wrapper manifest declares 1.3.289. Never claim a newer wrapper API,
        // even if a future system driver exposes 1.4 or a higher patch level.
        val parts = systemApi.split('.').map(String::toInt)
        val api = if (parts[0] > 1 || parts[1] > 3 || parts[1] == 3 && parts[2] > 289) "1.3.289" else systemApi
        // The wrapper opens the public Android loader. Never dlopen a private vendor ICD directly.
        env.toList().filter { it.startsWith("ADRENOTOOLS_") || it.startsWith("TU_") || it.startsWith("FD_") }
            .forEach(env::remove)
        listOf("WRAPPER_DEVICE_NAME", "WRAPPER_DEVICE_ID", "WRAPPER_VENDOR_ID", "WRAPPER_EXTENSION_BLACKLIST",
            "VK_DRIVER_FILES", "VK_ADD_DRIVER_FILES", "VK_INSTANCE_LAYERS", "DXVK_FILTER_DEVICE_NAME", "ENABLE_UTIL_LAYER").forEach(env::remove)
        env.put("WRAPPER_VK_VERSION", api)
        // This pinned wrapper uses category names, not the newer wrapper's severity levels.
        env.put("WRAPPER_LOG_LEVEL", "info,error,validation")
        env.put("WRAPPER_LOG_FILE", File(dir, "wrapper.log").absolutePath)
        // The stock Mali driver handles composite constants directly. Rewriting them
        // in this wrapper triggered a Wine assertion during WoW shader creation.
        env.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1")
        env.put("DXVK_LOG_LEVEL", "info")
        // WineUtils maps Z: to imagefs, not the host filesystem. The Mali profile
        // explicitly maps L: to this app's graphics_logs directory.
        env.put("DXVK_LOG_PATH", "L:\\")
        env.put("WINEDEBUG", "+timestamp,+pid,+tid,err+all,warn+vulkan,warn+d3d11,warn+dxgi")
        env.put("VK_LOADER_DEBUG", "error,warn,driver")
        env.put("WOW_VULKAN_WRAPPER", File(imageFs.libDir, "libvulkan_wrapper.so").absolutePath)
        env.put("WOW_VULKAN_LOG", File(dir, "vulkan-calls.log").absolutePath)
        val icd = File(dir, "mali-wrapper-icd.json")
        val library = File(context.applicationInfo.nativeLibraryDir, "libwow_vulkan.so")
        icd.writeText(org.json.JSONObject().apply {
            put("file_format_version", "1.0.0")
            put("ICD", org.json.JSONObject().apply { put("library_path", library.absolutePath); put("api_version", api) })
        }.toString(2))
        env.put("VK_ICD_FILENAMES", icd.absolutePath)
        env.put("VK_DRIVER_FILES", icd.absolutePath)
        Log.i("WoWVulkan", "Experimental Mali system backend; API=$api; ICD=$icd; wrapper=${env.get("WOW_VULKAN_WRAPPER")}")
    }

    /** Return the original diagnostic without inventing an unsupported-GPU explanation. */
    fun failureFromLines(lines: List<String>): String? {
        // A failed attempt followed by a successful device is a recovery, not a launch failure.
        val lastSuccess = lines.indexOfLast { "wrapper vkCreateDevice=VK_SUCCESS" in it }
        val active = if (lastSuccess >= 0) lines.drop(lastSuccess + 1) else lines
        val vkFailure = active.lastOrNull { line ->
            val lower = line.lowercase()
            ("vkcreateinstance=" in lower || "vkcreatedevice=" in lower) && "vk_success" !in lower
        }
        val extension = active.lastOrNull {
            val lower = it.lowercase()
            "required extension" in lower || "required vulkan extension" in lower || "required instance extensions not" in lower
        }
        if (vkFailure != null || extension != null) return listOfNotNull(extension, vkFailure).joinToString("\n").take(1500)
        return active.lastOrNull { line ->
            val lower = line.lowercase()
            listOf("failed to create device", "failed to create d3d11", "failed to create vulkan", "no adapters found",
                "failed to load vulkan", "error: dlopen bionic wrapper", "dxvk: no adapters", "no default adapter available").any { it in lower } ||
                "minimum required feature level" in lower && "not supported" in lower
        }?.take(1500)
    }

    fun lastFailure(context: Context): String? {
        val dir = directory(context)
        // Inspect bounded tails; shader logs can get large.
        val lines = dir.listFiles().orEmpty().filter { it.extension == "log" }.flatMap { f ->
            runCatching {
                java.io.RandomAccessFile(f, "r").use { input ->
                    input.seek((input.length() - 65536).coerceAtLeast(0))
                    val bytes = ByteArray((input.length() - input.filePointer).toInt())
                    input.readFully(bytes)
                    bytes.toString(Charsets.UTF_8).lines()
                }
            }.getOrDefault(emptyList())
        }
        return failureFromLines(lines)?.let { "$it\nGraphics logs: ${dir.absolutePath}" }
    }
}
