package omni.nes.gen

import omni.nes.validate.ValidationReport
import omni.nes.validate.ValidatorConfig
import java.io.File

/** One failed validator pass handed to a [Repairer]. */
data class FailedAttempt(
    val attempt: Int,
    val asm: String,
    /** Null when assembly or ROM construction failed before validation. */
    val report: ValidationReport?,
    /** Set when assembly/ROM-build failed (no report then). Null otherwise. */
    val buildError: String?,
)

/** A [Repairer]'s answer. `asm == null` means "cannot repair this". */
data class RepairOutcome(val asm: String?, val action: String)

/**
 * Turns a failed attempt into new assembly text (or gives up honestly).
 * Implementations must be deterministic and must never silently substitute
 * an unrelated program: every action is recorded verbatim in the attempt log.
 */
interface Repairer {
    val name: String
    fun repair(failed: FailedAttempt): RepairOutcome
}

/** One loop iteration, as written to the attempt log. */
data class AttemptRecord(
    val attempt: Int,
    val asmSha256: String,
    val assembled: Boolean,
    val buildError: String?,
    val passed: Boolean,
    /** Rule IDs that fired as FAIL on this attempt (empty when GREEN). */
    val firedRules: List<String>,
    /** What produced this attempt's asm: "initial generation" or a repair action. */
    val repairAction: String?,
)

/** The loop's final answer. `success` is the ONLY green signal. */
data class GenerationResult(
    val spec: GameSpec,
    val success: Boolean,
    val attempts: List<AttemptRecord>,
    val finalAsm: String?,
    /** Non-null only when [success] is true. */
    val finalRom: ByteArray?,
    /** Why the loop stopped early, if it did (e.g. no repair progress). */
    val note: String?,
)

/**
 * Loop knobs. Every behavior toggle here has an off-genuinely-means-off test
 * (GenerationLoopTest).
 */
data class LoopConfig(
    /** Hard cap on validator passes. The loop always terminates. */
    val maxAttempts: Int = 5,
    /**
     * When false, the [Repairer] is never consulted: every attempt is a fresh
     * `generate()` call and the raw generator output is all the loop judges.
     */
    val enableRepair: Boolean = true,
    /** When false, no attempt-log file is written anywhere. */
    val writeAttemptLog: Boolean = true,
    /** Directory for `<slug>-attempts.log` files (created on demand). */
    val logDir: File = File("gen-logs"),
    /** Passed straight through to [omni.nes.validate.NesValidator]. */
    val validatorConfig: ValidatorConfig = ValidatorConfig(),
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1 (got $maxAttempts): a zero/negative budget would silently validate nothing" }
    }
}
