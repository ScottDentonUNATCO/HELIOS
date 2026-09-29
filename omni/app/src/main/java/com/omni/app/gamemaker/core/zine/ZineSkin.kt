package com.omni.app.gamemaker.core

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Skin persistence for the zine design system. Dead-simple SharedPreferences
 * wrapper — no ViewModel, no new permissions.
 *
 * Stored values: "acid" | "beige" | "ransom" | "grunge". Default "beige"
 * (most readable perfected default). Unknown stored values resolve to beige
 * via [zineThemeForSkin] — never crash on a bad pref.
 */
class ZineSkinStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Currently selected skin key. */
    val skin: String
        get() = prefs.getString(KEY_SKIN, DEFAULT_SKIN) ?: DEFAULT_SKIN

    /** Persist a skin key; unknown keys are ignored. */
    fun setSkin(skinKey: String) {
        if (skinKey !in ZineThemes.keys) return
        prefs.edit().putString(KEY_SKIN, skinKey).apply()
    }

    companion object {
        const val SKIN_ACID = "acid"
        const val SKIN_BEIGE = "beige"
        const val SKIN_RANSOM = "ransom"
        const val SKIN_GRUNGE = "grunge"
        const val DEFAULT_SKIN = SKIN_BEIGE
        private const val PREFS_NAME = "helios_zine_skin"
        private const val KEY_SKIN = "skin"
    }
}

/**
 * A row of 4 labeled swatches — one per skin — each rendered in its own
 * skin's real paper/accent colors so Scott can see what he's picking.
 * The active skin gets a stamped ON badge.
 *
 * PLACEMENT (phase-2 agent): drop ZineSkinPicker() into MoreScreen's settings
 * section. Do NOT wire it into any other screen. The pick writes to
 * SharedPreferences and takes effect when the screen reloads; live
 * app-wide switching is phase-2 work.
 */
@Composable
fun ZineSkinPicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { ZineSkinStore(context) }
    var selected by remember { mutableStateOf(store.skin) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ZineSectionDivider("ZINE SKIN")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZineThemes.all.forEach { theme ->
                SkinSwatch(
                    theme = theme,
                    isSelected = theme.skinKey == selected,
                    onClick = {
                        store.setSkin(theme.skinKey)
                        selected = theme.skinKey
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = "Takes effect when the screen reloads.",
            style = MaterialTheme.typography.labelSmall,
            color = ZineTheme.current.onPaper.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun SkinSwatch(
    theme: ZineTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rot = remember(theme.skinKey) { (zineHash(theme.skinKey.hashCode(), 5) * 2f - 1f) * 2f }
    Column(
        modifier = modifier
            .graphicsLayer { rotationZ = rot }
            .clickable(role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.paper)
                .border(if (isSelected) 4.dp else 2.dp, if (isSelected) theme.accent1 else theme.ink)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(theme.accent1, theme.accent2, theme.accent3).forEach { c ->
                        Box(
                            Modifier
                                .padding(1.dp)
                                .background(c)
                                .border(1.dp, theme.ink)
                                .padding(7.dp),
                        )
                    }
                }
                Text(
                    text = theme.skinLabel.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = theme.onPaper,
                )
            }
            if (isSelected) {
                Text(
                    text = "ON",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = theme.onAccent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(theme.accent1)
                        .border(1.dp, theme.ink)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}
