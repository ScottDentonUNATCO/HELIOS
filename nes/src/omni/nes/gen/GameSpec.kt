package omni.nes.gen

/**
 * OMNI NES game-maker — Track A: validator-gated generation pipeline.
 *
 * A [GameSpec] is the user-facing description of the game to make. A
 * [Generator] turns a spec into 6502 assembly text in the syntax of
 * [omni.nes.asm.Assembler] (the ca65 subset documented in nes/README.md).
 * The [GenerationLoop] then assembles it, builds an iNES ROM, and runs it
 * through [omni.nes.validate.NesValidator] until the ROM is GREEN or the
 * attempt budget is exhausted.
 *
 * ## LLM integration seam (Phase 0 gateway)
 *
 * A real LLM-backed generator plugs in here later as:
 *
 * ```kotlin
 * class LlmGenerator(private val gateway: AiGateway) : Generator {
 *     override fun generate(spec: GameSpec): String {
 *         // System prompt: "Emit ONLY 6502 assembly in the OMNI assembler
 *         // syntax (labels, .org/.byte/.word/.res, all official mnemonics).
 *         // NROM-128: .org $C000, vectors at $FFFA. No commentary."
 *         // User prompt: spec rendered as text.
 *         // Route via the Phase 0 AiGateway router (provider registry,
 *         // spend tracking) and return the model's raw text.
 *     }
 * }
 * ```
 *
 * Nothing in the loop cares whether the text came from a template or a
 * model: `generate()` returning a String is the whole contract. A
 * [RepromptableGenerator] additionally receives the failed attempt (assembly
 * + validator findings) so a model can repair its own output; see
 * [RepromptRepair].
 */
data class GameSpec(
    val title: String,
    val genre: String = "",
    val controls: String = "",
    val mechanics: List<String> = emptyList(),
)

/** Produces assembly text for a [GameSpec]. Never throws on hostile specs. */
fun interface Generator {
    fun generate(spec: GameSpec): String
}

/**
 * Builds a [GameSpec] from a free-text brief — what the user typed into the
 * game-maker UI. The title is the brief's first 80 characters; mechanics are
 * keyword-scanned from the brief so the template generator can honor what
 * was actually asked for (a brief mentioning meteors must not silently
 * become the D-pad demo).
 *
 * Lives in the shared module (no Android dependencies) so the mapping is
 * unit-testable; the app's pipeline delegates to it.
 */
fun briefToSpec(brief: String): GameSpec {
    val clean = brief.replace(Regex("\\s+"), " ").trim()
    val b = clean.lowercase()
    fun any(vararg words: String) = words.any { it in b }
    val mechanics = buildList {
        if (any("dodge", "dodger", "meteor", "avoid", "collect", "star", "pick up"))
            addAll(listOf("dodge", "collect"))
        if (any("score", "points", "high score")) add("score")
        if (any("bounce", "bouncing", "ball", "pong")) add("bounce")
        if (any("move", "dpad", "d-pad", "control", "drive", "sprite", "ship", "fly"))
            add("move")
    }
    return GameSpec(
        title = clean.take(80).ifEmpty { "UNTITLED" },
        mechanics = mechanics,
    )
}

/**
 * A generator that can try again with failure context. The loop's
 * [RepromptRepair] calls this instead of applying rule-based patches.
 */
interface RepromptableGenerator : Generator {
    fun reprompt(spec: GameSpec, failure: FailedAttempt): String
}

/**
 * Optional: a generator may supply the 8 KB CHR image its tiles assume.
 * The loop uses it when building the ROM; otherwise a blank CHR is used.
 */
interface ChrProvider {
    fun chr(): ByteArray
}
