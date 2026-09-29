package com.omni.app.eyes

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omni.app.gamemaker.core.ZineBadge
import com.omni.app.gamemaker.core.ZineButton
import com.omni.app.gamemaker.core.ZineButtonStyle
import com.omni.app.gamemaker.core.ZineCard
import com.omni.app.gamemaker.core.ZineErrorCard
import com.omni.app.gamemaker.core.ZineScreenScaffold
import com.omni.app.gamemaker.core.LocalZineTheme
import com.omni.vision.ScreenCaptureService
import com.omni.vision.VisionConsentActivity
import kotlinx.coroutines.delay

/**
 * Agent eyes control surface.
 *
 * Start flow: "Start capture" launches [VisionConsentActivity] with an explicit
 * intent. That activity fires the OS consent dialog and, on grant, calls
 * [ScreenCaptureService.start] itself before finishing — the screen does not
 * need to relay the result. On denial the activity just finishes, so a start
 * with no capture flowing shortly after is surfaced as a consent-denied line
 * (N9) instead of silence.
 *
 * Honest limits: no frame pixels are ever rendered here (FPS/dims/status only).
 * The STOP button (and the persistent notification's STOP action) always works.
 */
@Composable
fun EyesScreen(onBack: () -> Unit, vm: EyesViewModel = viewModel()) {
    val context = LocalContext.current
    val status = vm.status
    val theme = LocalZineTheme.current

    // N9: denial reporting. VisionConsentActivity finishes without starting
    // capture when the user denies — detect that round-trip here and say so.
    var consentNote by remember { mutableStateOf<String?>(null) }
    var awaitingConsent by remember { mutableStateOf(false) }

    val startCapture = {
        consentNote = null
        awaitingConsent = true
        context.startActivity(Intent(context, VisionConsentActivity::class.java))
    }

    LaunchedEffect(awaitingConsent) {
        if (!awaitingConsent) return@LaunchedEffect
        delay(6000)
        awaitingConsent = false
        if (!vm.status.flowing) {
            consentNote = "Capture consent denied — tap Start capture and allow it."
        }
    }
    LaunchedEffect(status.flowing) {
        if (status.flowing) consentNote = null
    }

    ZineScreenScaffold(title = "Agent eyes", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(status)

            consentNote?.let { note ->
                ZineErrorCard(message = note, onRetry = startCapture)
            }

            ZineButton(
                text = if (status.flowing) "CAPTURE RUNNING" else "START CAPTURE",
                onClick = startCapture,
                enabled = !status.flowing,
                modifier = Modifier.fillMaxWidth(),
            )

            ZineButton(
                text = "STOP",
                onClick = { ScreenCaptureService.stop(context) },
                style = ZineButtonStyle.DANGER,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )

            Text(
                "Secure windows (banking, DRM video) appear black — Android enforces this, it can't be bypassed.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.onPaper.copy(alpha = 0.8f),
            )
            Text(
                "Android requires screen-capture consent again after reboot.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.onPaper.copy(alpha = 0.8f),
            )
            Text(
                "The persistent notification also carries a STOP button — capture can always be killed.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.onPaper.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun StatusCard(status: EyesStatus) {
    val theme = LocalZineTheme.current
    ZineCard(seed = 42) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = theme.onCard,
            )
            ZineBadge(if (status.flowing) "RUNNING" else "DARK")
        }
        if (status.flowing) {
            StatRow("Capture FPS", "%.1f".format(status.fps))
            StatRow("Latest frame", "${status.width} × ${status.height}")
            StatRow("Ring buffer", "${status.ringSize} / ${status.ringCapacity}")
        } else {
            Text(
                "Capture not running — no frames are flowing.",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.onCard.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val theme = LocalZineTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.onCard,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = theme.onCard,
        )
    }
}
