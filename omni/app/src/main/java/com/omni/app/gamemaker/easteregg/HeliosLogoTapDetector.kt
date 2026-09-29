package com.omni.app.gamemaker.easteregg

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Hidden-entry trigger for the Chinaskar v1 easter egg.
 *
 * Trigger: tap the Helios launcher/logo mark 5 times within 2 seconds.
 * See TRIGGER.md for the exact spec.
 *
 * [HeliosLogoTapDetector] is framework-agnostic: feed it every tap on the
 * logo and it fires [onTriggered] exactly once per qualifying burst.
 * The compose helper wraps it so a screen can do:
 *
 *   val onLogoTap = rememberEasterEggTapDetector { context.startActivity(Intent(context, ChinaskarV1Activity::class.java)) }
 *   IconButton(onClick = onLogoTap) { /* Helios logo */ }
 */
class HeliosLogoTapDetector(
    val requiredTaps: Int = 5,
    val windowMs: Long = 2_000L,
    private val onTriggered: () -> Unit,
) {
    private var taps = 0
    private var windowStart = 0L

    /** Call on every tap of the Helios logo mark. */
    fun tap(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - windowStart > windowMs) {
            taps = 0
            windowStart = nowMs
        }
        taps++
        if (taps >= requiredTaps) {
            taps = 0
            windowStart = 0L
            onTriggered()
        }
    }
}

@Composable
fun rememberEasterEggTapDetector(
    requiredTaps: Int = 5,
    windowMs: Long = 2_000L,
    onTriggered: () -> Unit,
): () -> Unit {
    val detector = remember { HeliosLogoTapDetector(requiredTaps, windowMs, onTriggered) }
    return { detector.tap() }
}
