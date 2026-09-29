package com.omni.app.gamemaker.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * HELIOS ZINE DESIGN SYSTEM — v7 (2026-09-24, Scott: "perfect the zine art").
 * v8 (2026-09-25): ransom skin hand-crafted from the hi-fi reference
 * (46659_0_1l4l.jpg) — see gamemaker/core/zine/RansomRefine.kt. The other
 * three skins are untouched.
 *
 * One cohesive punk-rock-zine system, four user-switchable skins, every tab a
 * page from the SAME zine. All decor is cheap Canvas vector drawing —
 * NO bitmaps, NO per-frame effects. All pseudo-randomness is a pure function
 * of (seed, index), so recomposition never jitters.
 *
 * QUICK USAGE (for the screen-restyling agents — pick by job, don't improvise):
 *   Screen chrome : ZineScreenScaffold(title, onBack) { ...content... }
 *   Grouping      : ZineCard { ... }                 — torn-paper card, tone varies
 *   Actions       : ZineButton("SAVE", onClick)      — PRIMARY / SECONDARY / DANGER
 *   On/off        : ZineToggle(checked, onCheckedChange, "Label") — stamped ON/OFF
 *   Input         : ZineTextField(value, onValueChange, label = "API key")
 *   Dividers      : ZineSectionDivider("SECTION")  or  ZineSectionDivider()
 *   Labels        : ZineBadge("NEW") · ZineStamp("TOP SECRET") · ZineHeader("BIG")
 *   Titles        : ZineRansomTitle / ZineNeonTitle / ZineGrungeTitle / ZineTitle
 *   Fasteners     : TapeStrip() (tape skins) · SafetyPin() (grunge skin)
 *   Decor         : InkSplatter() — screen corners only, keep subtle
 *   States        : ZineEmptyState("No keys yet") · ZineErrorCard(msg, onRetry)
 *   Skin picker   : ZineSkinPicker() — More screen ONLY, do not wire elsewhere
 *
 * THEMES: read `ZineTheme.current` inside any @Composable (requires a
 * ZineThemeProvider above it; without one you get the default beige skin).
 * Never hardcode zine colors in screens — always pull from the theme so skins
 * stay coherent. Body text MUST use theme.onPaper / theme.onCard (contrast is
 * guaranteed per skin); never light-gray-on-paper.
 *
 * SKINS ("acid" | "beige" | "ransom" | "grunge", default "beige"):
 *   acid   — mockup 1: neon green/purple on black, dripping psych type
 *   beige  — mockup 2: torn kraft paper, masking tape, rubber stamps (default)
 *   ransom — mockup 3: cut-out magazine letters, TOP SECRET dossier stamps
 *   grunge — mockup 4: hand-drawn marker, chromatic offset, safety pins
 *
 * PHASE-2 WIRING (do NOT do this in v7 — a separate agent does it):
 *   (a) MainActivity: wrap the Scaffold content —
 *         val skin = remember { ZineSkinStore(this).skin }
 *         ZineThemeProvider(zineThemeForSkin(skin)) { /* existing content */ }
 *   (b) MoreScreen: drop ZineSkinPicker() into the settings section.
 *   The picker writes the skin to SharedPreferences; it takes effect when the
 *   screen reloads (live switching is phase-2 work, not v7).
 */

/** How screen titles render per skin. */
enum class ZineTitleStyle { RANSOM, STAMP, NEON, MARKER }

/** What holds a ZineCard to the page. */
enum class ZineFastener { TAPE, PIN, NONE }

/**
 * The full look of one zine skin. Opinionated paper/ink pairs; every skin
 * guarantees strong text contrast (onPaper on paper, onCard on card,
 * onAccent on accent fills).
 */
data class ZineTheme(
    val skinKey: String,
    val skinLabel: String,
    val paper: Color,
    val onPaper: Color,
    val ink: Color,
    val card: Color,
    val cardTones: List<Color>,
    val onCard: Color,
    val accent1: Color,
    val accent2: Color,
    val accent3: Color,
    val onAccent: Color,
    val tape: Color,
    val stamp: Color,
    val fieldBg: Color,
    val fieldInk: Color,
    val titleStyle: ZineTitleStyle,
    val cardTiltDegrees: Float,
    val cardFastener: ZineFastener,
) {
    companion object
}

/** Mockup 1 — ACID NEON: neon green/purple on photocopier black. */
val ZineThemeAcid = ZineTheme(
    skinKey = "acid",
    skinLabel = "Acid Neon",
    paper = Color(0xFF0B0B0D),
    onPaper = Color(0xFFE9FFE0),
    ink = ZineAcid,
    card = Color(0xFF141A0E),
    cardTones = listOf(
        Color(0xFF141A0E), Color(0xFF1B2410),
        Color(0xFF101408), Color(0xFF1A1B10),
    ),
    onCard = Color(0xFFE9FFE0),
    accent1 = ZineAcid,
    accent2 = ZinePurple,
    accent3 = ZineYellow,
    onAccent = ZineBlack,
    tape = ZineYellow,
    stamp = Color(0xFFFF3D81),
    fieldBg = Color(0xFF1A2013),
    fieldInk = Color(0xFFF2FFE9),
    titleStyle = ZineTitleStyle.NEON,
    cardTiltDegrees = 1.5f,
    cardFastener = ZineFastener.NONE,
)

/** Mockup 2 — BEIGE COLLAGE: torn kraft paper, masking tape, rubber stamps. Default. */
val ZineThemeBeige = ZineTheme(
    skinKey = "beige",
    skinLabel = "Beige Collage",
    paper = Color(0xFFEDE0C8),
    onPaper = Color(0xFF1A1512),
    ink = Color(0xFF141210),
    card = ZinePaper,
    cardTones = listOf(
        ZinePaper,
        Color(0xFFE3B264),
        Color(0xFFE6A79E),
        Color(0xFF8FBCD4),
        Color(0xFFB9CF9F),
    ),
    onCard = Color(0xFF1A1512),
    accent1 = ZinePurple,
    accent2 = ZineStampRed,
    accent3 = ZineYellow,
    onAccent = Color(0xFFFFFFFF),
    tape = ZineTapeBeige,
    stamp = ZineStampRed,
    fieldBg = Color(0xFFFFFBF0),
    fieldInk = Color(0xFF1A1512),
    titleStyle = ZineTitleStyle.STAMP,
    cardTiltDegrees = 2.0f,
    cardFastener = ZineFastener.TAPE,
)

/** Mockup 3 — RANSOM NOTE: cut-out letters, dossier stamps, aged newsprint. */
val ZineThemeRansom = ZineTheme(
    skinKey = "ransom",
    skinLabel = "Ransom Note",
    paper = Color(0xFFDFD2B8),
    onPaper = Color(0xFF141210),
    ink = Color(0xFF141210),
    card = Color(0xFFF7F1E3),
    cardTones = listOf(
        Color(0xFFF7F1E3), Color(0xFFEFE3C8),
        Color(0xFFF1E8D2), Color(0xFFE9DCC0),
        // v8: the hi-fi reference mixes in clean white and blush-pink scraps
        Color(0xFFFDFDF8), Color(0xFFF3E3E6),
    ),
    onCard = Color(0xFF141210),
    accent1 = ZineBlue,
    accent2 = ZineStampRed,
    accent3 = Color(0xFFE88CA0),
    onAccent = Color(0xFFFFFFFF),
    tape = ZineTapeBeige,
    stamp = ZineStampRed,
    fieldBg = Color(0xFFFFFDF6),
    fieldInk = Color(0xFF141210),
    titleStyle = ZineTitleStyle.RANSOM,
    cardTiltDegrees = 2.5f,
    cardFastener = ZineFastener.TAPE,
)

/** Mockup 4 — MINIMAL GRUNGE: hand-drawn marker, chromatic offset, safety pins. */
val ZineThemeGrunge = ZineTheme(
    skinKey = "grunge",
    skinLabel = "Minimal Grunge",
    paper = Color(0xFFF5F0E6),
    onPaper = Color(0xFF1C1A17),
    ink = Color(0xFF1C1A17),
    card = Color(0xFFFCFAF4),
    cardTones = listOf(
        Color(0xFFFCFAF4), Color(0xFFF5F0E6),
        Color(0xFFFFFFFF), Color(0xFFF1EAD9),
    ),
    onCard = Color(0xFF1C1A17),
    accent1 = ZinePurple,
    accent2 = Color(0xFF1C1A17),
    accent3 = Color(0xFFD63A3A),
    onAccent = Color(0xFFFFFFFF),
    tape = Color(0xFFB9A7F2),
    stamp = Color(0xFF1C1A17),
    fieldBg = Color(0xFFFFFFFF),
    fieldInk = Color(0xFF1C1A17),
    titleStyle = ZineTitleStyle.MARKER,
    cardTiltDegrees = 1.0f,
    cardFastener = ZineFastener.PIN,
)

/** Registry of the four skins. Unknown keys fall back to beige (never crash). */
object ZineThemes {
    val all: List<ZineTheme> =
        listOf(ZineThemeAcid, ZineThemeBeige, ZineThemeRansom, ZineThemeGrunge)
    val keys: List<String> = all.map { it.skinKey }
    fun byKey(key: String): ZineTheme = all.firstOrNull { it.skinKey == key } ?: ZineThemeBeige
}

/** Resolve a stored skin key ("acid" | "beige" | "ransom" | "grunge") to a theme. */
fun zineThemeForSkin(skinKey: String): ZineTheme = ZineThemes.byKey(skinKey)

val LocalZineTheme = compositionLocalOf { ZineThemeBeige }

@Composable
fun ZineThemeProvider(theme: ZineTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalZineTheme provides theme, content = content)
}

/** Ambient theme accessor: `val theme = ZineTheme.current` inside @Composable. */
val ZineTheme.Companion.current: ZineTheme
    @Composable get() = LocalZineTheme.current

/**
 * Deterministic pseudo-random in [0, 1) from (seed, index). Pure function —
 * same inputs always give the same output, so decor never jitters across
 * recomposition. Not cryptographic, just stable scatter.
 */
internal fun zineHash(seed: Int, i: Int): Float {
    var h = seed * 0x9E3779B1.toInt() + i * 0x85EBCA6B.toInt()
    h = h xor (h ushr 15)
    h *= 0x2C1B3C6D.toInt()
    h = h xor (h ushr 12)
    return (h.toUInt() and 0xFFFFu).toFloat() / 65535f
}
