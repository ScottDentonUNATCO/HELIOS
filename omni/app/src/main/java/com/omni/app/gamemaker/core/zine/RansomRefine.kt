package com.omni.app.gamemaker.core

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * HELIOS v8 — hand-crafted ransom-note refinement.
 *
 * Studied from Scott's hi-fi reference (workspace/user/files/46659_0_1l4l.jpg):
 * cut-out magazine letters with visible paper texture (newsprint text lines,
 * halftone dots, aged yellow stock), torn edges and drop shadows, letters
 * overlapping like they were pasted in a hurry; semi-transparent masking-tape
 * strips with torn ends at odd angles; distressed double-stamped rubber
 * stamps; a layered collage bed of newspaper clippings, halftone patches,
 * red map scribbles and black redaction bars; lined-notebook-paper fields;
 * the purple-taped "Send" cutout.
 *
 * RULE: everything in this file renders ONLY when the active skin is
 * "ransom". Every call site branches on `theme.skinKey == "ransom"` and the
 * other three skins keep their exact v7 rendering. All decor is one-shot
 * Canvas vector drawing, deterministic per (seed, index) — no bitmaps, no
 * per-frame effects, no recomposition jitter.
 */

// ---------------------------------------------------------------------------
// Torn-polygon geometry (shared by scraps, tape, collage clippings)
// ---------------------------------------------------------------------------

/** Jagged closed polygon for an arbitrary rect — the "torn edge" primitive. */
internal fun tornRectPath(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    seed: Int,
    jagPx: Float,
): Path {
    val segs = 6
    val pts = ArrayList<Offset>(segs * 4 + 1)
    var k = 0
    val w = right - left
    val h = bottom - top
    for (i in 0..segs) pts += Offset(left + w * i / segs, top + zineHash(seed, k++) * jagPx)
    for (i in 1..segs) pts += Offset(right - zineHash(seed, k++) * jagPx, top + h * i / segs)
    for (i in 1..segs) pts += Offset(right - w * i / segs, bottom - zineHash(seed, k++) * jagPx)
    for (i in 1..segs) pts += Offset(left + zineHash(seed, k++) * jagPx, bottom - h * i / segs)
    return Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        close()
    }
}

/**
 * One cut-out scrap: torn paper polygon + drop shadow + paper texture
 * (0 = plain white, 1 = newsprint text lines, 2 = halftone dots, 3 = aged
 * yellow) + thin cut edge. Drawn behind the letter it backs.
 */
fun Modifier.ransomScrap(
    bg: Color,
    texture: Int,
    seed: Int,
    jag: Dp = 2.5.dp,
): Modifier = this.drawBehind {
    val jagPx = jag.toPx()
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawBehind
    // Drop shadow: same torn shape, offset down-right.
    drawPath(
        tornRectPath(2.dp.toPx(), 3.dp.toPx(), w + 2.dp.toPx(), h + 3.dp.toPx(), seed, jagPx),
        Color.Black.copy(alpha = 0.22f),
    )
    val path = tornRectPath(0f, 0f, w, h, seed, jagPx)
    drawPath(path, bg)
    when (texture) {
        1 -> { // newsprint: faint column rules
            var y = 5.dp.toPx()
            val lg = 6.5.dp.toPx()
            val lc = Color(0xFF8A8A8A).copy(alpha = 0.55f)
            var lk = 0
            while (y < h - 3.dp.toPx()) {
                val inset = 3.dp.toPx() + zineHash(seed, 700 + lk) * 5.dp.toPx()
                drawLine(lc, Offset(inset, y), Offset(w - 3.dp.toPx(), y), 1.dp.toPx())
                y += lg
                lk++
            }
        }
        2 -> { // halftone dot screen
            val gap = 5.5.dp.toPx()
            var yy = gap * 0.5f
            var row = 0
            val dc = Color(0xFF444444).copy(alpha = 0.5f)
            while (yy < h) {
                var xx = gap * 0.5f + if (row % 2 == 1) gap * 0.5f else 0f
                while (xx < w) {
                    drawCircle(dc, 1.dp.toPx(), Offset(xx, yy))
                    xx += gap
                }
                yy += gap
                row++
            }
        }
    }
    // The cut edge.
    drawPath(path, Color(0xFF141210).copy(alpha = 0.30f), style = Stroke(1.dp.toPx()))
}

// ---------------------------------------------------------------------------
// Cut-out letters
// ---------------------------------------------------------------------------

private val RansomInks = listOf(
    ZineBlack,
    ZineStampRed,
    ZineBlue,
    ZinePurple,
    Color(0xFFE88CA0), // the pink "e" in the reference "Key"
    ZineBlack,
)

private val RansomFamilies = listOf(
    FontFamily.Serif,
    FontFamily.SansSerif,
    FontFamily.Monospace,
    FontFamily.Serif,
)

/**
 * A single ransom-note cut-out letter: its own scrap of magazine paper
 * (texture varies per index), its own ink color and typeface, slight
 * rotation, torn edges, drop shadow. Letters after the first tuck 3.dp
 * under the previous one — the pasted-in-a-hurry overlap.
 *
 * Deterministic per index: recomposition never jitters.
 */
@Composable
fun RansomCutLetter(
    char: Char,
    index: Int,
    fontSize: TextUnit = 34.sp,
    modifier: Modifier = Modifier,
) {
    if (char == ' ') {
        Spacer(Modifier.width((fontSize.value * 0.4f).dp))
        return
    }
    val ink = RansomInks[(zineHash(index, 511) * RansomInks.size).toInt()]
    val family = RansomFamilies[(zineHash(index, 512) * RansomFamilies.size).toInt()]
    val weight = if (zineHash(index, 513) > 0.5f) FontWeight.Black else FontWeight.ExtraBold
    val rot = (zineHash(index, 514) * 2f - 1f) * 7f
    val texture = (zineHash(index, 515) * 4).toInt()
    val bg = if (texture == 3) Color(0xFFF1E3B8) else Color(0xFFFDFDF8)
    Text(
        text = char.toString(),
        fontSize = fontSize,
        fontFamily = family,
        fontWeight = weight,
        color = ink,
        modifier = modifier
            .offset(x = if (index == 0) 0.dp else (-3).dp)
            .graphicsLayer { rotationZ = rot }
            .ransomScrap(bg = bg, texture = texture, seed = index)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** Ransom-note title: every letter its own scrap, sizes vary like the reference "Helios". */
@Composable
fun RansomTitle(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        text.forEachIndexed { i, c ->
            RansomCutLetter(c, i, fontSize = (30 + zineHash(i, 510) * 16).sp)
        }
    }
}

// ---------------------------------------------------------------------------
// Collage bed (screen background layer)
// ---------------------------------------------------------------------------

/**
 * Layered collage bed: newspaper clippings, halftone patches, red map
 * scribbles, black redaction bars — the reference's busy background, kept
 * at low alpha so content stays readable. One Canvas, one draw pass,
 * deterministic per seed.
 */
fun Modifier.ransomCollage(seed: Int = 11): Modifier = this.drawBehind {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawBehind
    val tones = listOf(
        Color(0xFFF7F1E3), Color(0xFFEFE3C8), Color(0xFFFDFDF8),
        Color(0xFFE9DCC0), Color(0xFFF1E3B8),
    )
    // Clippings: torn scraps scattered across the page.
    for (i in 0 until 12) {
        val cw = (0.16f + zineHash(seed, 600 + i * 7) * 0.30f) * w
        val ch = (0.05f + zineHash(seed, 601 + i * 7) * 0.11f) * h
        val x = zineHash(seed, 602 + i * 7) * (w - cw)
        val y = zineHash(seed, 603 + i * 7) * (h - ch)
        val tone = tones[(zineHash(seed, 604 + i * 7) * tones.size).toInt()]
        drawPath(
            tornRectPath(x, y, x + cw, y + ch, seed + i, 5.dp.toPx()),
            tone.copy(alpha = 0.5f),
        )
        when ((zineHash(seed, 605 + i * 7) * 3).toInt()) {
            0 -> { // newsprint column rules inside the clipping
                var ly = y + 6.dp.toPx()
                val lg = 7.dp.toPx()
                val lc = Color(0xFF777777).copy(alpha = 0.35f)
                while (ly < y + ch - 4.dp.toPx()) {
                    drawLine(lc, Offset(x + 5.dp.toPx(), ly), Offset(x + cw - 5.dp.toPx(), ly), 1.dp.toPx())
                    ly += lg
                }
            }
            1 -> { // halftone patch inside the clipping
                var yy = y + 4.dp.toPx()
                val g = 6.dp.toPx()
                var row = 0
                val dc = Color(0xFF666666).copy(alpha = 0.30f)
                while (yy < y + ch) {
                    var xx = x + 4.dp.toPx() + if (row % 2 == 1) g * 0.5f else 0f
                    while (xx < x + cw) {
                        drawCircle(dc, 1.dp.toPx(), Offset(xx, yy))
                        xx += g
                    }
                    yy += g
                    row++
                }
            }
            // 2 -> plain aged paper, nothing more
        }
    }
    // Red map scribbles: wandering polylines, like the reference's fragments.
    for (i in 0 until 2) {
        val mp = Path()
        var mx = zineHash(seed, 640 + i * 10) * w
        var my = zineHash(seed, 641 + i * 10) * h
        mp.moveTo(mx, my)
        for (k in 1..5) {
            mx += (zineHash(seed, 642 + i * 10 + k) - 0.5f) * w * 0.3f
            my += (zineHash(seed, 648 + i * 10 + k) - 0.5f) * h * 0.2f
            mp.lineTo(mx, my)
        }
        drawPath(mp, Color(0xFFC1272D).copy(alpha = 0.40f), style = Stroke(2.dp.toPx()))
    }
    // Redaction bars.
    for (i in 0 until 3) {
        val bw = (0.10f + zineHash(seed, 650 + i) * 0.18f) * w
        val bh = 14.dp.toPx()
        val bx = zineHash(seed, 651 + i) * (w - bw)
        val by = zineHash(seed, 652 + i) * h
        drawRect(Color(0xFF111111).copy(alpha = 0.85f), Offset(bx, by), Size(bw, bh))
    }
}

/**
 * Shared screen backdrop: theme paper + (ransom: layered collage bed) +
 * corner ink splatter. Used by ZineScreenScaffold and the custom-Box
 * screens (chat, hub, claim ledger) so the ransom skin is a complete theme.
 */
@Composable
fun ZineScreenBackdrop() {
    val theme = ZineTheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.paper),
    ) {
        if (theme.skinKey == "ransom") {
            Box(Modifier.fillMaxSize().ransomCollage(seed = 11))
        }
        InkSplatter(
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            seed = 7,
            alpha = 0.10f,
        )
        InkSplatter(
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            seed = 21,
            alpha = 0.08f,
        )
    }
}

// ---------------------------------------------------------------------------
// Distressed rubber stamp (ransom skin)
// ---------------------------------------------------------------------------

/** Broken-border stamp ink: dashes with deterministic gaps, like a worn stamp. */
fun Modifier.ransomStampBorder(ink: Color, seed: Int): Modifier = this.drawBehind {
    val sw = 3.dp.toPx()
    var k = 0
    var x = 0f
    while (x < size.width) {
        val len = 6.dp.toPx() + zineHash(seed, k++) * 10.dp.toPx()
        if (zineHash(seed, k++) > 0.28f) {
            val xe = min(x + len, size.width)
            drawLine(ink, Offset(x, sw / 2), Offset(xe, sw / 2), sw)
            drawLine(ink, Offset(x, size.height - sw / 2), Offset(xe, size.height - sw / 2), sw)
        }
        x += len + 4.dp.toPx()
    }
    var y = 0f
    while (y < size.height) {
        val len = 6.dp.toPx() + zineHash(seed, k++) * 10.dp.toPx()
        if (zineHash(seed, k++) > 0.28f) {
            val ye = min(y + len, size.height)
            drawLine(ink, Offset(sw / 2, y), Offset(sw / 2, ye), sw)
            drawLine(ink, Offset(size.width - sw / 2, y), Offset(size.width - sw / 2, ye), sw)
        }
        y += len + 4.dp.toPx()
    }
}

/**
 * The reference's "UNATCO" / "TOP SECRET DOSSIER": distressed red ink,
 * double-stamped ghost behind it, crooked.
 */
@Composable
fun RansomStamp(text: String, color: Color, modifier: Modifier = Modifier) {
    val ink = color.copy(alpha = 0.88f)
    Box(modifier.graphicsLayer { rotationZ = -7f }) {
        // Ghost of a second stamp hit, slightly off-register.
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = ink.copy(alpha = 0.28f),
            modifier = Modifier
                .offset(2.dp, 2.dp)
                .graphicsLayer { rotationZ = 2f },
        )
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = ink,
            modifier = Modifier
                .ransomStampBorder(ink, text.hashCode())
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Lined notebook paper (ransom fields)
// ---------------------------------------------------------------------------

/** Horizontal rules + red margin line over a field — the reference's message strip. */
fun Modifier.ransomLinedPaper(): Modifier = this.drawBehind {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawBehind
    val rule = Color(0xFF7FA8C9).copy(alpha = 0.55f)
    val gap = 24.dp.toPx()
    var y = gap
    while (y < h) {
        drawLine(rule, Offset(0f, y), Offset(w, y), 1.dp.toPx())
        y += gap
    }
    drawLine(
        Color(0xFFE88CA0).copy(alpha = 0.7f),
        Offset(28.dp.toPx(), 0f),
        Offset(28.dp.toPx(), h),
        1.5.dp.toPx(),
    )
}

// ---------------------------------------------------------------------------
// Purple-tape cutout button (ransom PRIMARY — the reference's "Send")
// ---------------------------------------------------------------------------

/**
 * The reference's "Send": cut-out letters pasted on a strip of purple
 * masking tape with torn edges. Used for PRIMARY buttons under the ransom
 * skin only; SECONDARY/DANGER keep their v7 rendering.
 */
@Composable
fun RansomTapeButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val rot = remember { (zineHash(31, 3) * 2f - 1f) * 2f }
    val tapeAlpha = if (enabled) 0.90f else 0.35f
    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = rot }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .drawBehind {
                drawPath(
                    tornRectPath(0f, 0f, size.width, size.height, 777, 4.dp.toPx()),
                    Color(0xFF7C3AED).copy(alpha = tapeAlpha),
                )
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            text.forEachIndexed { i, c ->
                RansomCutLetter(c, i + 100, fontSize = 20.sp)
            }
        }
    }
}

/** Torn masking-tape strip with fiber striations — the ransom card fastener. */
@Composable
fun RansomTapeStrip(
    modifier: Modifier = Modifier,
    angle: Float = -24f,
    seed: Int = 99,
) {
    val tape = ZineTheme.current.tape
    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = angle }
            .drawBehind {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@drawBehind
                drawPath(
                    tornRectPath(0f, 0f, w, h, seed, 3.dp.toPx()),
                    tape.copy(alpha = 0.82f),
                )
                // Tape fiber striations.
                var x = 4.dp.toPx()
                val fc = Color(0xFF9C8B66).copy(alpha = 0.35f)
                var k = 0
                while (x < w - 2.dp.toPx()) {
                    val x2 = x + (zineHash(seed, k++) - 0.5f) * 4.dp.toPx()
                    drawLine(fc, Offset(x, 2.dp.toPx()), Offset(x2, h - 2.dp.toPx()), 1.dp.toPx())
                    x += 6.dp.toPx()
                }
            }
            .width(72.dp)
            .padding(vertical = 11.dp),
    )
}
