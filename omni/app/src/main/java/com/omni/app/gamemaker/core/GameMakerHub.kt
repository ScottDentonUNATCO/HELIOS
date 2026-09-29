package com.omni.app.gamemaker.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.app.gamemaker.knowledge.KnowledgeSeeder
import kotlinx.coroutines.delay

/**
 * TRACK C — the ONE entry screen for the whole game-maker module.
 *
 * Sections, in order:
 * 1. Easter-egg teaser (a hint, never the trigger itself).
 * 2. NES Game Maker entry — the only working pipeline. The actual maker
 *    screen belongs to Track B; this hub only fires [onOpenNesMaker], which
 *    the parent wires to navigation (this file never touches MainActivity).
 * 3. Console plugin list with honest status badges, straight from
 *    [PluginRegistry].
 * 4. Knowledge-base status — direct call to the knowledge track's
 *    [KnowledgeSeeder.isSeeded]; the parent seeds once at app start via
 *    KnowledgeSeeder.ensureSeeded(appContext, sharedMemoryStore).
 *
 * No new permissions. No disk/network access. Pure UI over the registry.
 */
@Composable
fun GameMakerHubScreen(
    onBack: () -> Unit = {},
    onOpenNesMaker: () -> Unit = {},
) {
    val plugins = remember { PluginRegistry.plugins }
    val context = LocalContext.current
    // N17: the knowledge base is seeded asynchronously at app start, so a
    // one-shot remember() can show "pending" for the whole visit. Re-check
    // a few times after composition; steady-state cost is zero.
    var knowledgeLabel by remember { mutableStateOf(knowledgeStatusLabel(context)) }
    LaunchedEffect(Unit) {
        repeat(6) {
            delay(1500)
            val fresh = knowledgeStatusLabel(context)
            if (fresh != knowledgeLabel) knowledgeLabel = fresh
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("‹ Back") }
            ZineRansomTitle("Game Maker")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Describe a game, get a real ROM — generated, validated, and screenshot-tested on-device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 1. easter egg teaser ----
        // The real trigger is documented in easteregg/TRIGGER.md and wired by
        // the parent (logo tap detector). This card hints without revealing.
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ZineTape("🥚 Hidden treasure")
                    Text(
                        text = "Rumor: the Helios logo likes attention. " +
                            "Give it a rapid burst of taps and see what happens.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // ---- 2. NES maker entry ----
        item {
            val nes = PluginRegistry.get("nes")
            Box(modifier = Modifier.fillMaxWidth()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "NES Game Maker",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        PluginBadge(PluginStatus.READY, "")
                    }
                    Text(
                        text = nes?.tagline
                            ?: "Make real, validatable NES games on-device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onOpenNesMaker,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open NES Game Maker")
                    }
                }
                }
                // Masking tape pinning the card's top-left corner, collage-style.
                TapeStrip(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 28.dp, y = (-9).dp),
                )
            }
        }

        // ---- 3. console plugins ----
        item {
            Text(
                "Consoles",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        items(plugins, key = { it.id }) { plugin ->
            PluginCard(plugin = plugin)
        }

        // ---- 4. knowledge base status ----
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Knowledge base",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = knowledgeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (knowledgeLabel == KNOWLEDGE_READY)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The knowledge base feeds the maker real hardware docs — 6502, PPU, " +
                    "mapper 0, the validator's rule book. Seeded once at first launch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PluginCard(plugin: ConsolePlugin) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = plugin.displayName,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                PluginBadge(plugin.status, plugin.statusNote)
            }
            Text(
                text = plugin.tagline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (plugin.status != PluginStatus.READY && plugin.statusNote.isNotBlank()) {
                Text(
                    text = plugin.statusNote,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PluginBadge(status: PluginStatus, note: String) {
    val (text, color) = when (status) {
        PluginStatus.READY -> "READY" to MaterialTheme.colorScheme.primary
        PluginStatus.COMING_SOON -> "COMING SOON" to Color.Gray
        PluginStatus.EXPERIMENTAL -> "PLANNED SCOPE" to MaterialTheme.colorScheme.tertiary
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

private const val KNOWLEDGE_READY = "knowledge: ready"
private const val KNOWLEDGE_PENDING = "knowledge: pending"

/**
 * Knowledge-base status, direct against the knowledge track's
 * [KnowledgeSeeder]. "ready" once the parent has seeded the store at app
 * start (KnowledgeSeeder.ensureSeeded); "pending" until then. The hub never
 * seeds itself — seeding needs the shared MemoryStore, which the parent owns.
 */
private fun knowledgeStatusLabel(context: android.content.Context): String =
    if (KnowledgeSeeder.isSeeded(context)) KNOWLEDGE_READY else KNOWLEDGE_PENDING
