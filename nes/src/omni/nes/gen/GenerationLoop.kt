package omni.nes.gen

import omni.nes.asm.AssembleError
import omni.nes.asm.Assembler
import omni.nes.rom.RomBuilder
import omni.nes.validate.NesValidator
import omni.nes.validate.ValidationReport
import java.io.File
import java.security.MessageDigest

/**
 * The validator-gated generation loop: spec -> generator -> assembler ->
 * iNES ROM -> [NesValidator] -> repair/iterate until GREEN or the attempt
 * budget is exhausted.
 *
 * Guarantees (each covered by a test):
 * - The loop always terminates (bounded attempts; identical-output and
 *   no-repair-progress early stops).
 * - `success == true` IFF the final ROM passed the validator with zero
 *   FAILs. No green claim is ever made on unvalidated output.
 * - Generator exceptions and hostile/empty output become honest RED
 *   attempts, never crashes.
 * - Every attempt is logged with its assembly hash, verdict, fired rule
 *   IDs, and the repair action that produced it.
 */
class GenerationLoop(
    private val generator: Generator,
    private val repairer: Repairer,
    private val config: LoopConfig = LoopConfig(),
) {

    fun run(spec: GameSpec, slug: String = slugify(spec.title)): GenerationResult {
        val attempts = mutableListOf<AttemptRecord>()
        var asm: String
        try {
            asm = generator.generate(spec)
        } catch (e: Exception) {
            return failFast(spec, slug, attempts, "generator threw on generate(): ${e.message}")
        }
        var repairAction: String? = "initial generation"
        var note: String? = null
        var finalRom: ByteArray? = null
        var finalAsm: String? = null
        var success = false

        for (n in 1..config.maxAttempts) {
            val sha = sha256(asm)
            var assembled = true
            var buildError: String? = null
            var report: ValidationReport? = null
            var rom: ByteArray? = null
            try {
                val prg = Assembler.assemble(asm).bytes
                val chr = (generator as? ChrProvider)?.chr() ?: ByteArray(8192)
                rom = RomBuilder.build(prg, chr)
                report = NesValidator(config.validatorConfig).validate(rom)
            } catch (e: AssembleError) {
                assembled = false
                buildError = "assemble: ${e.message}"
            } catch (e: IllegalArgumentException) {
                buildError = "rom build: ${e.message}"
            } catch (e: Exception) {
                buildError = "unexpected: ${e.javaClass.simpleName}: ${e.message}"
            }

            val passed = report?.passed == true
            val fired = report?.fails()?.map { it.ruleId }?.sorted() ?: emptyList()
            attempts.add(
                AttemptRecord(
                    attempt = n,
                    asmSha256 = sha,
                    assembled = assembled,
                    buildError = buildError,
                    passed = passed,
                    firedRules = fired,
                    repairAction = repairAction,
                ),
            )

            if (passed) {
                success = true
                finalAsm = asm
                finalRom = rom
                break
            }
            if (n == config.maxAttempts) {
                note = "attempt budget exhausted (${config.maxAttempts} attempts, all RED)"
                break
            }

            val failed = FailedAttempt(n, asm, report, buildError)
            val next: String?
            val action: String
            if (!config.enableRepair) {
                try {
                    next = generator.generate(spec)
                } catch (e: Exception) {
                    note = "generator threw on re-generate(): ${e.message}; stopping"
                    break
                }
                action = "regenerated (repair disabled)"
            } else {
                val outcome = try {
                    repairer.repair(failed)
                } catch (e: Exception) {
                    RepairOutcome(null, "repairer threw: ${e.message}")
                }
                if (outcome.asm == null) {
                    note = "repair made no progress (${outcome.action}); stopping"
                    break
                }
                next = outcome.asm
                action = outcome.action
            }

            if (sha256(next) == sha) {
                note = "repair '${action?.take(80)}' produced output identical to attempt $n " +
                    "(hash $sha); stopping to avoid a wasted validation cycle"
                break
            }
            asm = next
            repairAction = action
        }

        val result = GenerationResult(
            spec = spec,
            success = success,
            attempts = attempts,
            finalAsm = finalAsm,
            finalRom = finalRom,
            note = note,
        )
        writeLog(slug, result)
        return result
    }

    private fun failFast(
        spec: GameSpec,
        slug: String,
        attempts: List<AttemptRecord>,
        note: String,
    ): GenerationResult {
        val result = GenerationResult(spec, false, attempts, null, null, note)
        writeLog(slug, result)
        return result
    }

    // ------------------------------------------------------------------
    // attempt log
    // ------------------------------------------------------------------

    private fun writeLog(slug: String, result: GenerationResult) {
        if (!config.writeAttemptLog) return
        val dir = config.logDir
        dir.mkdirs()
        val file = File(dir, "$slug-attempts.log")
        val sb = StringBuilder()
        sb.appendLine("# OMNI NES generation attempt log")
        sb.appendLine("spec_title: ${result.spec.title}")
        sb.appendLine("spec_genre: ${result.spec.genre}")
        sb.appendLine("spec_mechanics: ${result.spec.mechanics.joinToString(",")}")
        sb.appendLine("generator: ${generator.javaClass.name}")
        sb.appendLine("repairer: ${repairer.name}")
        sb.appendLine(
            "config: maxAttempts=${config.maxAttempts} " +
                "enableRepair=${config.enableRepair} " +
                "validator=${config.validatorConfig}",
        )
        for (a in result.attempts) {
            sb.appendLine("---")
            sb.appendLine("attempt: ${a.attempt}")
            sb.appendLine("asm_sha256: ${a.asmSha256}")
            sb.appendLine("assembled: ${a.assembled}")
            sb.appendLine("build_error: ${a.buildError ?: "none"}")
            sb.appendLine("passed: ${a.passed}")
            sb.appendLine("fired_rules: ${if (a.firedRules.isEmpty()) "none" else a.firedRules.joinToString(",")}")
            sb.appendLine("repair_action: ${a.repairAction ?: "none"}")
        }
        sb.appendLine("---")
        sb.appendLine("result: ${if (result.success) "SUCCESS" else "FAILURE"} after ${result.attempts.size} attempt(s)")
        sb.appendLine("note: ${result.note ?: "none"}")
        file.writeText(sb.toString())
    }

    companion object {
        fun sha256(text: String): String {
            val d = MessageDigest.getInstance("SHA-256")
            return d.digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }

        fun slugify(title: String): String {
            val s = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            return s.ifEmpty { "untitled" }.take(40)
        }
    }
}
