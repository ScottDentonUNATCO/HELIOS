package com.omni.app.gamemaker.core

/**
 * One-tap build gate: the plug-in contract check.
 *
 * Before the NES one-tap flow compiles anything, the target [ConsolePlugin]
 * must prove — against adversarial inputs, not happy paths — that it honors
 * the [ConsolePlugin] contract:
 *
 * - status must be READY (anything else fails with the plugin's own
 *   [ConsolePlugin.statusNote] quoted verbatim — the honest label).
 * - validate() must NEVER throw, even on garbage input (contract:
 *   "Never throws for bad code").
 * - build()/test()/export()/generate() must REFUSE bad input with a clear,
 *   non-blank message instead of crashing or faking success.
 *
 * Pure Kotlin, no Android dependency: the check runs on the build machine
 * against stub/test-double plugins, and on-device against the real NES
 * plugin before a one-tap run.
 */
data class ContractCheckItem(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

data class ContractCheckResult(
    val pluginId: String,
    val passed: Boolean,
    val items: List<ContractCheckItem>,
) {
    /** Human-readable failure summary for the UI / logs. */
    fun failureSummary(): String =
        items.filter { !it.passed }.joinToString("; ") { "${it.name}: ${it.detail}" }
}

fun checkConsoleContract(plugin: ConsolePlugin): ContractCheckResult {
    val items = mutableListOf<ContractCheckItem>()

    fun check(name: String, block: () -> ContractCheckItem) {
        items += try {
            block()
        } catch (e: Exception) {
            ContractCheckItem(name, false, "check itself threw: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    check("status is READY") {
        if (plugin.status == PluginStatus.READY) {
            ContractCheckItem("status is READY", true, "${plugin.displayName} reports READY")
        } else {
            ContractCheckItem(
                "status is READY", false,
                "plugin reports ${plugin.status}; honest label: \"${plugin.statusNote}\"",
            )
        }
    }

    check("validate() never throws on garbage") {
        return@check try {
            val report = plugin.validate(GameCode(language = "test", sources = emptyMap()))
            ContractCheckItem(
                "validate() never throws on garbage", true,
                "returned passed=${report.passed} with ${report.issues.size} issue(s), no throw",
            )
        } catch (e: Exception) {
            ContractCheckItem(
                "validate() never throws on garbage", false,
                "threw ${e.javaClass.simpleName}: ${e.message} — contract says validate never throws",
            )
        }
    }

    check("build() refuses empty source with a clear message") {
        refusesWithMessage(
            name = "build() refuses empty source with a clear message",
            call = { plugin.build(GameCode(language = "test", sources = emptyMap())) },
        )
    }

    check("test() refuses empty ROM with a clear message") {
        refusesWithMessage(
            name = "test() refuses empty ROM with a clear message",
            call = { plugin.test(ByteArray(0)) },
        )
    }

    check("export() refuses empty ROM with a clear message") {
        refusesWithMessage(
            name = "export() refuses empty ROM with a clear message",
            call = { plugin.export(ByteArray(0)) },
        )
    }

    check("generate() refuses a blank title with a clear message") {
        refusesWithMessage(
            name = "generate() refuses a blank title with a clear message",
            call = {
                // generate is suspend; run it blocking for the contract check.
                kotlinx.coroutines.runBlocking {
                    plugin.generate(GameBrief(title = "   ", description = "x"))
                }
            },
        )
    }

    return ContractCheckResult(
        pluginId = plugin.id,
        passed = items.all { it.passed },
        items = items,
    )
}

private fun refusesWithMessage(name: String, call: () -> Unit): ContractCheckItem {
    return try {
        call()
        ContractCheckItem(name, false, "accepted bad input silently — must refuse with a clear message")
    } catch (e: UnsupportedOperationException) {
        // Honest stubs refuse via the status note — that IS the contract for
        // non-READY plugins (the status check above already failed them).
        ContractCheckItem(name, !e.message.isNullOrBlank(), "refused honestly: \"${e.message}\"")
    } catch (e: IllegalArgumentException) {
        ContractCheckItem(name, !e.message.isNullOrBlank(), "refused: \"${e.message}\"")
    } catch (e: IllegalStateException) {
        ContractCheckItem(name, !e.message.isNullOrBlank(), "refused: \"${e.message}\"")
    } catch (e: Exception) {
        ContractCheckItem(
            name, false,
            "refused with an unexpected ${e.javaClass.simpleName}: ${e.message} — " +
                "use IllegalArgumentException with a clear message",
        )
    }
}
