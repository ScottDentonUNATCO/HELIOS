package com.omni.app.gamemaker.core

/**
 * TRACK C — honest stubs for consoles that are NOT implemented yet.
 *
 * Rule, no exceptions: a stub NEVER fakes support. Every method throws
 * [UnsupportedOperationException] with the exact UI label, and the hub
 * shows [ConsolePlugin.statusNote] verbatim as the badge text.
 */

/** Shared base: all pipeline methods refuse, loudly and honestly. */
abstract class ComingSoonPlugin : ConsolePlugin {
    override val status: PluginStatus = PluginStatus.COMING_SOON
    override val statusNote: String = "not yet — NES first, this console is coming"

    protected fun nope(): Nothing =
        throw UnsupportedOperationException(statusNote)

    override suspend fun generate(brief: GameBrief): GameCode = nope()
    override fun validate(code: GameCode): ValidationReport = nope()
    override fun build(code: GameCode): ByteArray = nope()
    override fun test(rom: ByteArray): TestReport = nope()
    override fun export(rom: ByteArray): ExportResult = nope()
}

/** Sega Genesis / Mega Drive — stub. Motorola 68000 backend not built. */
class GenesisPlugin : ComingSoonPlugin() {
    override val id: String = "genesis"
    override val displayName: String = "Sega Genesis"
    override val tagline: String = "16-bit blast processing — planned after the NES pipeline is solid."
}

/** Super Nintendo — stub. 65c816 backend not built. */
class SnesPlugin : ComingSoonPlugin() {
    override val id: String = "snes"
    override val displayName: String = "Super Nintendo"
    override val tagline: String = "Mode 7 dreams — planned after the NES pipeline is solid."
}

/** Nintendo Game Boy — stub. LR35902 backend not built. */
class GameBoyPlugin : ComingSoonPlugin() {
    override val id: String = "gameboy"
    override val displayName: String = "Game Boy"
    override val tagline: String = "Pocket-sized games — planned after the NES pipeline is solid."
}

/**
 * Android — scoped HONESTLY, not as a console.
 *
 * On-device kotlinc/d8 APK building is not feasible inside this app, so
 * this plugin will never assemble APKs here. Its planned scope is guided
 * project generation + export: produce a Gradle project skeleton the user
 * builds on a PC. Until that generator exists, every method refuses with
 * the honest label below.
 */
class AndroidPlugin : ConsolePlugin {
    override val id: String = "android"
    override val displayName: String = "Android (guided project)"
    override val tagline: String = "Guided Android project generation + export."
    override val status: PluginStatus = PluginStatus.EXPERIMENTAL
    override val statusNote: String =
        "Planned scope: guided project generation + export only — " +
            "on-device kotlinc/d8 APK building is not feasible."

    private fun nope(): Nothing = throw UnsupportedOperationException(statusNote)

    override suspend fun generate(brief: GameBrief): GameCode = nope()
    override fun validate(code: GameCode): ValidationReport = nope()
    override fun build(code: GameCode): ByteArray = nope()
    override fun test(rom: ByteArray): TestReport = nope()
    override fun export(rom: ByteArray): ExportResult = nope()
}
