package omni.nes.gen

/**
 * Deterministic, surgical, text-level repair for the failures a sloppy
 * generator most often produces. Each patch is keyed to validator rule IDs
 * and is a pure function on the assembly text:
 *
 * - VEC_NMI_IN_PRG / VEC_RESET_IN_PRG -> ensure a vector table exists at
 *   $FFFA (replacing a broken/zeroed tail, or appending one with default
 *   NMI/RESET/IRQ handlers when labels are missing).
 * - NMI_ENABLED -> inject CLI + `$2000` bit-7 enable right after the RESET
 *   label (fixes both "NMI never enabled" and "enabled but I flag stuck").
 *
 * Deliberately NOT handled here: ILLEGAL_OPCODE / BOOT_* faults, sprite
 * overflow, palette timing. Locating an illegal opcode's source line from a
 * bare address has no reliable text-level answer without a source map, so
 * pretending to patch it would be slop. Those go to [RepromptRepair] (ask the
 * generator again with the findings) or end the run honestly.
 */
class RuleBasedRepair : Repairer {
    override val name: String = "RuleBasedRepair"

    override fun repair(failed: FailedAttempt): RepairOutcome {
        val report = failed.report
            ?: return RepairOutcome(null, "no validator report (assembly/ROM build failed); nothing surgical applies")
        val fails = report.fails().map { it.ruleId }.toSet()
        var asm = failed.asm
        val actions = mutableListOf<String>()

        // Cascading-failure guard: when the boot halted early (illegal opcode
        // executed, stack fault, bus fault, PC escape) or an illegal opcode is
        // reachable, downstream rules like NMI_ENABLED fire spuriously (the
        // program never got far enough for NMI to matter). Patching the
        // symptom then would mask the root cause and waste the budget, so the
        // NMI patch is gated on a boot that actually ran to steady state.
        val bootHalted = fails.any { it == "ILLEGAL_OPCODE" || it.startsWith("BOOT_") }

        if ("VEC_NMI_IN_PRG" in fails || "VEC_RESET_IN_PRG" in fails) {
            val fixed = ensureVectors(asm)
            if (fixed != null) {
                asm = fixed.first
                actions.add(fixed.second)
            }
        }
        if ("NMI_ENABLED" in fails && !bootHalted) {
            val fixed = ensureNmiEnabled(asm)
            if (fixed != null) {
                asm = fixed.first
                actions.add(fixed.second)
            }
        }

        return if (actions.isEmpty()) {
            RepairOutcome(null, "no rule-based patch applies to: ${fails.sorted().joinToString(",")}")
        } else {
            RepairOutcome(asm, actions.joinToString("; "))
        }
    }

    // ------------------------------------------------------------------
    // vector table
    // ------------------------------------------------------------------

    private val fffaOrg = Regex("""(?m)^\s*\.org\s+\$(?:fffa|FFFA)\s*$""")
    private fun hasLabel(asm: String, name: String) =
        Regex("""(?m)^$name:""", RegexOption.IGNORE_CASE).containsMatchIn(asm)

    /**
     * Returns (newAsm, action) with a working vector table, or null when the
     * table exists and looks intentional (repair must not second-guess it).
     */
    internal fun ensureVectors(asm: String): Pair<String, String>? {
        val m = fffaOrg.find(asm)
        val needNmi = !hasLabel(asm, "nmi")
        val needReset = !hasLabel(asm, "reset")
        val needIrq = !hasLabel(asm, "irq")
        val handlers = buildString {
            if (needNmi) appendLine("NMI:\n    RTI")
            if (needIrq) appendLine("IRQ:\n    RTI")
            if (needReset) {
                appendLine("RESET:")
                appendLine("    SEI")
                appendLine("    CLD")
                appendLine("    LDX #\$FF")
                appendLine("    TXS")
                appendLine("    CLI")
                appendLine("    LDA #%10000000")
                appendLine("    STA \$2000           ; repair: NMI on")
                appendLine("    LDA #%00011110")
                appendLine("    STA \$2001")
                appendLine("rs_loop:")
                appendLine("    JMP rs_loop")
            }
        }
        val table = ".org \$FFFA\n    .word NMI, RESET, IRQ\n"
        return if (m != null) {
            // A vector region exists but is broken/zeroed: replace the whole
            // tail from the .org onward. (Anything after $FFFA can only be the
            // vector table; PRG ends at $FFFF.)
            val head = asm.substring(0, m.range.first).trimEnd()
            Pair(
                head + "\n\n; --- repair: vector table replaced ---\n" + handlers + table,
                "replaced broken vector tail at \$FFFA" +
                    if (handlers.isNotEmpty()) " (+default handlers)" else "",
            )
        } else {
            Pair(
                asm.trimEnd() + "\n\n; --- repair: missing vector table appended ---\n" +
                    handlers + table,
                "appended missing vector table at \$FFFA" +
                    if (handlers.isNotEmpty()) " (+default handlers)" else "",
            )
        }
    }

    // ------------------------------------------------------------------
    // NMI enable
    // ------------------------------------------------------------------

    private val resetLabel = Regex("""(?m)^reset:""", RegexOption.IGNORE_CASE)
    private val mainLabel = Regex("""(?m)^main:""", RegexOption.IGNORE_CASE)

    /**
     * Injects CLI + `$2000` bit-7 enable just before the main loop (falling
     * back to right after RESET when there is no main loop). The late anchor
     * matters: a generator bug that writes `$2000` with the wrong value
     * during init is fixed because the correct enable runs *after* it (last
     * write wins). A second enable when one already exists is idempotent.
     */
    internal fun ensureNmiEnabled(asm: String): Pair<String, String>? {
        val anchor = mainLabel.find(asm) ?: resetLabel.find(asm) ?: return null
        // Before `main:` -> insert ahead of the label; after `RESET:` -> insert behind it.
        val isMain = anchor.value.equals("main:", ignoreCase = true)
        val at = if (isMain) anchor.range.first else anchor.range.last + 1
        val inject = "    ; --- repair: ensure NMI can actually fire ---\n" +
            "    CLI\n" +
            "    LDA #%10000000\n" +
            "    STA \$2000           ; repair: NMI enable\n"
        val where = if (isMain) "before main loop" else "after RESET"
        return Pair(
            asm.substring(0, at) + inject + asm.substring(at),
            "injected CLI + NMI enable $where",
        )
    }
}

/**
 * The re-prompt hook: hands the failure (assembly + validator findings) back
 * to a [RepromptableGenerator] and uses whatever it returns. This is the
 * repair path for failures with no surgical fix (illegal opcodes, boot
 * faults, sprite overflow, palette timing).
 */
class RepromptRepair(
    private val spec: GameSpec,
    private val generator: RepromptableGenerator,
) : Repairer {
    override val name: String = "RepromptRepair"
    override fun repair(failed: FailedAttempt): RepairOutcome {
        val rules = failed.report?.fails()?.map { it.ruleId }?.sorted()?.joinToString(",")
            ?: "build failed: ${failed.buildError}"
        val next = generator.reprompt(spec, failed)
        return RepairOutcome(next, "re-prompted generator with failure context ($rules)")
    }
}

/** Tries each repairer in order; the first non-null result wins. */
class CompositeRepair(private vararg val repairers: Repairer) : Repairer {
    override val name: String =
        "CompositeRepair(${repairers.joinToString(",") { it.name }})"

    override fun repair(failed: FailedAttempt): RepairOutcome {
        var last: RepairOutcome? = null
        for (r in repairers) {
            val out = r.repair(failed)
            if (out.asm != null) return out.copy(action = "[${r.name}] ${out.action}")
            last = out
        }
        return RepairOutcome(null, last?.action ?: "no repairers configured")
    }
}
