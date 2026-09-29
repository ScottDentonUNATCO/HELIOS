package com.omni.app.gamemaker.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Screen chrome
// ---------------------------------------------------------------------------

/**
 * Full-screen zine page: theme paper background, corner ink splatter, a title
 * rendered in the skin's title style, and a taped "< BACK" strip when onBack
 * is supplied. Never a blank screen — pair with ZineEmptyState for empty lists.
 *
 * Usage: ZineScreenScaffold(title = "Socket board", onBack = { ... }) { ... }
 */
@Composable
fun ZineScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = ZineTheme.current
    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {
        // v8: shared backdrop — theme paper + ransom collage bed + ink corners.
        ZineScreenBackdrop()
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
            ) {
                if (onBack != null) {
                    ZineBackStrip(onBack)
                    Spacer(Modifier.width(10.dp))
                }
                ZineThemedTitle(title, Modifier.weight(1f))
                actions()
            }
            ZineSectionDivider()
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ZineBackStrip(onBack: () -> Unit) {
    val theme = ZineTheme.current
    Box(
        modifier = Modifier
            .graphicsLayer { rotationZ = -4f }
            .background(theme.tape.copy(alpha = 0.92f))
            .border(1.dp, theme.ink.copy(alpha = 0.5f))
            .clickable(role = Role.Button, onClick = onBack)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "< BACK",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = theme.ink,
        )
    }
}

@Composable
private fun ZineThemedTitle(text: String, modifier: Modifier = Modifier) {
    when (ZineTheme.current.titleStyle) {
        ZineTitleStyle.RANSOM -> ZineRansomTitle(text, modifier)
        ZineTitleStyle.STAMP -> ZineHeader(text, modifier)
        ZineTitleStyle.NEON -> ZineNeonTitle(text, modifier)
        ZineTitleStyle.MARKER -> ZineGrungeTitle(text, modifier)
    }
}

/**
 * Mockup 1 title: heavy neon type with a hard ink offset shadow, slightly
 * crooked — the dripping-psych look without any bitmap work.
 */
@Composable
fun ZineNeonTitle(text: String, modifier: Modifier = Modifier) {
    val theme = ZineTheme.current
    Box(modifier.graphicsLayer { rotationZ = -2f }) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = theme.ink,
            modifier = Modifier.offset(3.dp, 3.dp),
        )
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = theme.accent1,
        )
    }
}

/**
 * Mockup 4 title: hand-drawn marker wordmark with a chromatic offset —
 * cyan/magenta ghost copies behind the ink, like a misregistered print.
 */
@Composable
fun ZineGrungeTitle(text: String, modifier: Modifier = Modifier) {
    val theme = ZineTheme.current
    Box(modifier.graphicsLayer { rotationZ = -1f }) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = Color(0xFF00A8B5).copy(alpha = 0.6f),
            modifier = Modifier.offset((-2).dp, 0.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = Color(0xFFE0407A).copy(alpha = 0.6f),
            modifier = Modifier.offset(2.dp, 0.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = theme.ink,
        )
    }
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/**
 * Torn-paper card: slightly rotated, jagged Canvas edge, tone picked from the
 * skin's cardTones (pass seed = list index for variety in lists), held down
 * with the skin's fastener (tape / safety pin / none).
 *
 * Usage: ZineCard(seed = index) { Text(...); ZineButton(...) }
 */
@Composable
fun ZineCard(
    modifier: Modifier = Modifier,
    seed: Int = 0,
    toneIndex: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = ZineTheme.current
    val bg = remember(seed, theme) {
        val tones = theme.cardTones
        if (toneIndex != null && tones.isNotEmpty()) tones[toneIndex % tones.size] else theme.card
    }
    val rot = remember(seed, theme) {
        (zineHash(seed, 1) * 2f - 1f) * theme.cardTiltDegrees
    }
    Box(modifier.graphicsLayer { rotationZ = rot }) {
        Column(
            Modifier
                .fillMaxWidth()
                .tornPaper(background = bg, edgeColor = theme.ink.copy(alpha = 0.55f), seed = seed)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
        when (theme.cardFastener) {
            ZineFastener.TAPE -> {
                // v8: the ransom skin pins cards with torn tape at varied
                // angles; every other skin keeps its exact v7 strip.
                val isRansom = theme.skinKey == "ransom"
                TapeStrip(
                    Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = (-10).dp),
                    angle = if (isRansom) (zineHash(seed, 77) * 2f - 1f) * 32f else -24f,
                    torn = isRansom,
                )
            }
            ZineFastener.PIN -> SafetyPin(
                Modifier.align(Alignment.TopCenter).offset(y = (-8).dp),
            )
            ZineFastener.NONE -> Unit
        }
    }
}

/**
 * Torn-paper edge: fills the composable with a jagged polygon and strokes it.
 * Cheap (one Path, ~32 segments) and fully deterministic per seed.
 */
fun Modifier.tornPaper(
    background: Color,
    edgeColor: Color,
    seed: Int = 0,
    jag: androidx.compose.ui.unit.Dp = 7.dp,
): Modifier = this.drawBehind {
    val jagPx = jag.toPx()
    val w = size.width
    val h = size.height
    val segs = 7
    val pts = ArrayList<Offset>(segs * 4 + 1)
    var k = 0
    for (i in 0..segs) { // top edge, left -> right
        pts += Offset(w * i / segs, zineHash(seed, k++) * jagPx)
    }
    for (i in 1..segs) { // right edge, top -> bottom
        pts += Offset(w - zineHash(seed, k++) * jagPx, h * i / segs)
    }
    for (i in 1..segs) { // bottom edge, right -> left
        pts += Offset(w - w * i / segs, h - zineHash(seed, k++) * jagPx)
    }
    for (i in 1..segs) { // left edge, bottom -> top
        pts += Offset(zineHash(seed, k++) * jagPx, h - h * i / segs)
    }
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        close()
    }
    drawPath(path, background)
    drawPath(path, edgeColor, style = Stroke(width = 1.6.dp.toPx()))
}

// ---------------------------------------------------------------------------
// Buttons, toggles, fields
// ---------------------------------------------------------------------------

enum class ZineButtonStyle { PRIMARY, SECONDARY, DANGER }

/**
 * Chunky stamp button — thick ink border, slight crooked tilt, clearly
 * tappable. PRIMARY = skin accent, SECONDARY = card cut-out, DANGER = red ink.
 * v8: under the ransom skin, PRIMARY buttons render as the reference's
 * purple-taped "Send" — cut-out letters on torn purple tape. SECONDARY and
 * DANGER keep their v7 rendering on every skin.
 */
@Composable
fun ZineButton(
    text: String,
    onClick: () -> Unit,
    style: ZineButtonStyle = ZineButtonStyle.PRIMARY,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val theme = ZineTheme.current
    if (theme.skinKey == "ransom" && style == ZineButtonStyle.PRIMARY) {
        RansomTapeButton(text, onClick, enabled, modifier)
        return
    }
    val (bg, fg) = when (style) {
        ZineButtonStyle.PRIMARY -> theme.accent1 to theme.onAccent
        ZineButtonStyle.SECONDARY -> theme.card to theme.onCard
        ZineButtonStyle.DANGER -> theme.accent2 to Color.White
    }
    val rot = remember(style) { (zineHash(style.ordinal, 3) * 2f - 1f) * 1.5f }
    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = rot }
            .background(if (enabled) bg else bg.copy(alpha = 0.35f))
            .border(3.dp, theme.ink)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = if (enabled) fg else fg.copy(alpha = 0.5f),
        )
    }
}

/**
 * A toggle that genuinely reads on/off at a glance: ON is a filled accent
 * stamp, OFF is a hollow outline. Never rely on color alone — the word is
 * stamped right on it.
 */
@Composable
fun ZineToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val theme = ZineTheme.current
    Row(
        modifier = modifier
            .clickable(role = Role.Switch, onClick = { onCheckedChange(!checked) })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(if (checked) theme.accent1 else Color.Transparent)
                .border(3.dp, if (checked) theme.ink else theme.ink.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (checked) "ON" else "OFF",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = if (checked) theme.onAccent else theme.ink.copy(alpha = 0.55f),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = theme.onPaper,
        )
    }
}

/**
 * Taped-label text field: a masking-tape label strip over an ink-bordered
 * input box. For secrets (API keys) pass
 * visualTransformation = PasswordVisualTransformation().
 */
@Composable
fun ZineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val theme = ZineTheme.current
    Column(modifier) {
        Box(
            modifier = Modifier
                .graphicsLayer { rotationZ = -1.5f }
                .background(theme.tape.copy(alpha = 0.9f))
                .border(1.dp, theme.ink.copy(alpha = 0.4f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = theme.ink,
            )
        }
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            textStyle = TextStyle(color = theme.fieldInk, fontSize = 16.sp),
            cursorBrush = SolidColor(theme.accent1),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(theme.fieldBg)
                        // v8: ransom fields are lined notebook paper.
                        .then(if (theme.skinKey == "ransom") Modifier.ransomLinedPaper() else Modifier)
                        .border(2.dp, theme.ink)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.fieldInk.copy(alpha = 0.45f),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Dividers, badges
// ---------------------------------------------------------------------------

/**
 * Caution-tape divider: diagonal ink/accent stripes full-bleed, with an
 * optional paper label chip stamped in the middle.
 */
@Composable
fun ZineSectionDivider(text: String? = null, modifier: Modifier = Modifier) {
    val theme = ZineTheme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (text != null) 30.dp else 12.dp)
            .drawBehind {
                val stripe = 14.dp.toPx()
                var x = -size.height
                var i = 0
                while (x < size.width + size.height) {
                    drawLine(
                        color = if (i % 2 == 0) theme.accent3 else theme.ink,
                        start = Offset(x, size.height),
                        end = Offset(x + size.height, 0f),
                        strokeWidth = stripe,
                    )
                    x += stripe
                    i++
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = theme.ink,
                modifier = Modifier
                    .background(theme.paper)
                    .border(2.dp, theme.ink)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Small cut-out badge for capability tags, counts, status chips.
 */
@Composable
fun ZineBadge(text: String, modifier: Modifier = Modifier, seed: Int = 0) {
    val theme = ZineTheme.current
    val rot = remember(seed) { (zineHash(seed, 9) * 2f - 1f) * 4f }
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Black,
        color = theme.onAccent,
        modifier = modifier
            .graphicsLayer { rotationZ = rot }
            .background(theme.accent1)
            .border(2.dp, theme.ink)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ---------------------------------------------------------------------------
// Fasteners & decor (Canvas vector only — cheap, deterministic)
// ---------------------------------------------------------------------------

/**
 * Safety-pin fastener, mockup 4 style: coil + body + clasp, drawn with Canvas.
 * Used automatically on ZineCard by the grunge skin; place manually elsewhere.
 */
@Composable
fun SafetyPin(modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val c = if (color == Color.Unspecified) ZineTheme.current.ink else color
    Canvas(modifier.size(46.dp, 20.dp)) {
        val sw = 2.4.dp.toPx()
        val coilC = Offset(8.dp.toPx(), 10.dp.toPx())
        drawCircle(c, radius = 6.dp.toPx(), center = coilC, style = Stroke(sw))
        drawLine(c, Offset(13.dp.toPx(), 13.dp.toPx()), Offset(38.dp.toPx(), 13.dp.toPx()), sw)
        drawLine(c, Offset(13.dp.toPx(), 7.dp.toPx()), Offset(36.dp.toPx(), 12.dp.toPx()), sw)
        drawCircle(c, 2.6.dp.toPx(), Offset(39.dp.toPx(), 13.dp.toPx()), style = Stroke(sw))
    }
}

/**
 * A few deterministic ink blobs for screen corners. Subtle by default —
 * decor, not content. Never place over tappable UI.
 */
@Composable
fun InkSplatter(
    modifier: Modifier = Modifier,
    seed: Int = 7,
    alpha: Float = 0.12f,
    color: Color = Color.Unspecified,
) {
    val c = (if (color == Color.Unspecified) ZineTheme.current.ink else color).copy(alpha = alpha)
    Canvas(modifier.size(130.dp)) {
        val s = size
        for (i in 0 until 6) {
            drawCircle(
                c,
                (4 + zineHash(seed, i + 40) * 16).dp.toPx(),
                Offset(zineHash(seed, i * 2) * s.width, zineHash(seed, i * 2 + 1) * s.height),
            )
        }
        for (i in 0 until 9) {
            drawCircle(
                c,
                (1.5f + zineHash(seed, 140 + i) * 3).dp.toPx(),
                Offset(
                    zineHash(seed, 100 + i * 2) * s.width,
                    zineHash(seed, 100 + i * 2 + 1) * s.height,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// States
// ---------------------------------------------------------------------------

/** Never a blank screen: stamped "NOTHING HERE" + the caller's message. */
@Composable
fun ZineEmptyState(text: String, modifier: Modifier = Modifier) {
    val theme = ZineTheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZineStamp("NOTHING HERE")
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.onPaper,
        )
    }
}

/** Actionable error: red-ink card, "BUSTED" stamp, message, retry button. */
@Composable
fun ZineErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = ZineTheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.card)
            .border(3.dp, theme.stamp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZineStamp("BUSTED")
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.onCard,
        )
        ZineButton("TRY AGAIN", onRetry, ZineButtonStyle.DANGER)
    }
}
