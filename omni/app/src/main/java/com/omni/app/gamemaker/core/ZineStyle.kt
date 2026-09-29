package com.omni.app.gamemaker.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Punk rock zine styling for Helios (Scott's 2026-09-24 design direction),
 * matched to his four canonical mockups:
 *   - acid neon psych (neon green/purple, dripping type, halftone chaos)
 *   - cut-and-paste beige (torn paper, masking tape, rubber stamps)
 *   - ransom-note collage (cut-out magazine letters, TOP SECRET stamps)
 *   - minimal grunge (hand-drawn marker, chromatic offset, safety pins)
 * Mockup files: workspace/user/files/46660_0_g42j.webp,
 * 46661_1_usk8.webp, 46659_2_xma5.webp, 46662_0_rgvr.webp.
 *
 * This file holds the ORIGINAL starter pieces (ZineHeader, ZineTitle,
 * ZineTape, ZineStamp, TapeStrip, ZineRansomTitle + color vals) — all still
 * working, now theme-aware where noted. The full v7 design system lives in
 * gamemaker/core/zine/: ZineTheme.kt (4 skins, ZineThemeProvider,
 * ZineTheme.current), ZineComponents.kt (scaffold, card, button, toggle,
 * field, divider, badge, pin, splatter, empty/error states), ZineSkin.kt
 * (ZineSkinStore, ZineSkinPicker). See the usage doc at the top of
 * ZineTheme.kt before building screens.
 *
 * Nothing here changes layout behavior or function — loud type, tape,
 * and stamps over the same working UI.
 */

/** High-voltage safety-yellow, straight off a photocopied gig flyer. */
val ZineYellow = Color(0xFFFFEB3B)

/** Photocopier black. */
val ZineBlack = Color(0xFF111111)

/** Acid neon green (mockup 1). */
val ZineAcid = Color(0xFF39FF14)

/** Deep psych purple (mockup 1). */
val ZinePurple = Color(0xFF7C3AED)

/** Rubber-stamp red (mockups 2 + 3). */
val ZineStampRed = Color(0xFFC1272D)

/** Ransom-note blue (mockup 3's cut-out "e"). */
val ZineBlue = Color(0xFF1D4ED8)

/** Masking-tape beige (mockup 2). */
val ZineTapeBeige = Color(0xFFD9C9A3)

/** Torn notebook paper (mockup 2). */
val ZinePaper = Color(0xFFF4ECD8)

/**
 * A section header that looks cut out of a zine and slapped on with tape:
 * heavy black type on yellow, slightly crooked, black border.
 */
@Composable
fun ZineHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Black,
        color = ZineBlack,
        modifier = modifier
            .graphicsLayer { rotationZ = -1.5f }
            .background(ZineYellow)
            .border(3.dp, ZineBlack)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * A smaller tag for screen titles inside top app bars: white on black,
 * crooked like a pasted-on cut-out label.
 */
@Composable
fun ZineTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Black,
        color = Color.White,
        modifier = modifier
            .graphicsLayer { rotationZ = 1.2f }
            .background(ZineBlack)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * A caution-tape strip divider: black text on yellow, slightly rotated.
 */
@Composable
fun ZineTape(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Black,
        color = ZineBlack,
        modifier = modifier
            .graphicsLayer { rotationZ = -0.8f }
            .background(ZineYellow)
            .border(2.dp, ZineBlack)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * A rubber-stamp label: bordered, rotated, slightly transparent red ink.
 * Straight from the ransom-note mockup's "TOP SECRET DOSSIER" stamp.
 * Theme-aware: the default color follows the active skin's stamp ink
 * (beige skin = the classic ZineStampRed, so existing screens look identical).
 * v8: under the ransom skin this renders as a distressed double-stamped
 * mark (RansomStamp) — other skins keep the exact v7 rendering.
 */
@Composable
fun ZineStamp(
    text: String,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    val inkBase = if (color == Color.Unspecified) ZineTheme.current.stamp else color
    if (ZineTheme.current.skinKey == "ransom") {
        RansomStamp(text, inkBase, modifier)
        return
    }
    val ink = inkBase.copy(alpha = 0.88f)
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Black,
        color = ink,
        modifier = modifier
            .graphicsLayer { rotationZ = -7f }
            .border(3.dp, ink)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * A strip of masking tape, for pinning over card corners collage-style.
 * Place inside a Box with align + offset, overlapping the card edge.
 * Theme-aware: defaults to the active skin's tape color (beige skin =
 * the classic ZineTapeBeige, so existing screens look identical).
 * v8: angle/torn params — the ransom skin uses torn ends at varied angles;
 * defaults preserve the exact v7 look for every other caller.
 */
@Composable
fun TapeStrip(
    modifier: Modifier = Modifier,
    color: Color? = null,
    angle: Float = -24f,
    torn: Boolean = false,
) {
    val tape = color ?: ZineTheme.current.tape
    if (torn) {
        RansomTapeStrip(modifier, angle = angle)
        return
    }
    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = angle }
            .background(tape.copy(alpha = 0.78f))
            .size(width = 72.dp, height = 22.dp)
            .border(1.dp, Color(0xFF9C8B66).copy(alpha = 0.6f)),
    )
}

/**
 * Ransom-note title: every letter its own cut-out scrap — mixed sizes,
 * rotations, ink colors, typefaces and paper textures, deterministic per
 * index so recomposition never jitters. v8: hand-crafted cut letters from
 * the hi-fi reference (newsprint, halftone, aged stock, torn edges,
 * drop shadows, pasted-overlap). Only used by the ransom skin.
 */
@Composable
fun ZineRansomTitle(text: String, modifier: Modifier = Modifier) {
    RansomTitle(text, modifier)
}
