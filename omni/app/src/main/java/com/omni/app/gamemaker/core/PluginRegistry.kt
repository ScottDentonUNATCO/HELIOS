package com.omni.app.gamemaker.core

/**
 * TRACK C — registry of console plugins.
 *
 * The NES plugin is the full implementation; everything else is an honest
 * stub. Order matters: READY plugins first, then planned ones, so the hub
 * always leads with what actually works.
 */
object PluginRegistry {

    val plugins: List<ConsolePlugin> = listOf(
        NesConsolePlugin(),
        GenesisPlugin(),
        SnesPlugin(),
        GameBoyPlugin(),
        AndroidPlugin(),
    )

    /** Look up a plugin by [ConsolePlugin.id], or null. */
    fun get(id: String): ConsolePlugin? = plugins.firstOrNull { it.id == id }

    /** Plugins whose pipeline genuinely works end-to-end. */
    val ready: List<ConsolePlugin>
        get() = plugins.filter { it.status == PluginStatus.READY }

    /** The default console for "just make me a game". Always NES for now. */
    val default: ConsolePlugin
        get() = get("nes") ?: plugins.first()
}
