package app.gamenative.ui.screen.wow

/** Selection is independent of the device marketing name; never assign Turnip to Mali. */
internal enum class WowGraphicsBackend {
    TURNIP, MALI_SYSTEM, UNSUPPORTED;

    companion object {
        fun detect(renderer: String, soc: String, hardware: String): WowGraphicsBackend {
            if (renderer.contains("mali", true) || renderer.contains("immortalis", true)) return MALI_SYSTEM
            if (renderer.contains("adreno", true)) return TURNIP
            // Some firmware fails to expose an EGL renderer before the first surface exists.
            // A MediaTek SoC is a fallback only when the GPU is unknown, not for a known different GPU.
            val unknown = renderer.isBlank() || renderer.equals("unknown", true)
            if (unknown && (soc.contains("mediatek", true) || soc.contains("dimensity", true) ||
                    listOf(soc, hardware).any { it.startsWith("mt", true) && it.drop(2).firstOrNull()?.isDigit() == true })) return MALI_SYSTEM
            if (unknown && (listOf("SM8735", "SM8750", "SM8845", "SM8850").any { soc.contains(it, true) } ||
                    hardware.equals("sun", true))) return TURNIP
            return UNSUPPORTED
        }
    }
}
