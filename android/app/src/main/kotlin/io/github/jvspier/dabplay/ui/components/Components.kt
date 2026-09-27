package io.github.jvspier.dabplay.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.layout.ContentScale
import io.github.jvspier.dabplay.ui.theme.LocalNight
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.Heart
import io.github.jvspier.dabplay.data.ServiceKey
import io.github.jvspier.dabplay.ui.theme.Accent
import io.github.jvspier.dabplay.ui.theme.LocalDabColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** Press state from the design: scale 0.96, touch only (no hover). */
@Composable
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) 0.96f else 1f, tween(90), label = "press")
    // read in the layer block: the press animation redraws only, no recomposition per frame
    return this.graphicsLayer { scaleX = scale.value; scaleY = scale.value }.clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

private val LOGO_PALETTE = listOf(Accent.orange, Accent.blue, Accent.amber, Accent.green, Accent.lavender, Accent.pink)

/**
 * Station logo: the downloaded logo when there is one (fading in over the initials when it arrives), else an
 * initials tile in the station's palette colour. Opaque logos fill the tile; transparent ones sit on a light
 * "paper" tile, because they're drawn for light web pages (Qmusic's grey "music" vanishes on the night theme).
 */
@Composable
fun StationLogo(label: String, key: ServiceKey?, size: Dp, radius: Dp = 10.dp, modifier: Modifier = Modifier) {
    val logo = rememberLogo(key)
    val shape = RoundedCornerShape(radius)
    Crossfade(logo, modifier.size(size), animationSpec = tween(250), label = "logo") { img ->
        when {
            img == null -> Initials(label, key, size, shape)
            img.fullBleed -> Image(img.bitmap, contentDescription = label, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape))
            else -> Box(Modifier.fillMaxSize().clip(shape).background(LOGO_PAPER)
                .then(if (LocalNight.current) Modifier else Modifier.border(1.dp, LocalDabColors.current.neutral800.copy(alpha = 0.35f), shape)),
                contentAlignment = Alignment.Center) {
                Image(img.bitmap, contentDescription = label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(size * 0.06f))
            }
        }
    }
}

private val LOGO_PAPER = Color(0xFFF4F4F7)

@Composable
private fun Initials(label: String, key: ServiceKey?, size: Dp, shape: RoundedCornerShape) {
    val col = LOGO_PALETTE[abs((key?.sid ?: label.hashCode().toLong()).toInt()) % LOGO_PALETTE.size]
    val initials = remember(label) {
        val words = label.split(' ', '-', '.').filter { it.isNotBlank() }
        when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}"
            label.length >= 2 -> label.take(2)
            else -> label
        }.uppercase()
    }
    Box(Modifier.fillMaxSize().clip(shape).background(col.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(initials, color = col, fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The main action, the same style everywhere: orange gradient circle, spinner ring while tuning. */
@Composable
fun PlayButton(playing: Boolean, size: Dp, onClick: () -> Unit, busy: Boolean = false) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size)
                .shadow(size * 0.25f, CircleShape, ambientColor = Accent.playTo, spotColor = Accent.playTo)
                .background(Brush.linearGradient(listOf(Accent.playFrom, Accent.playTo)), CircleShape)
                .pressable(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play, contentDescription = if (playing) "Pause" else "Play",
                tint = Accent.playIcon, modifier = Modifier.size(size * 0.44f))
        }
        if (busy) {
            val t = rememberInfiniteTransition(label = "spin")
            val angle = t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
            Canvas(Modifier.size(size + 12.dp).graphicsLayer { rotationZ = angle.value }) {
                drawArc(Accent.orange, 0f, 100f, false, style = Stroke(width = 4.dp.toPx()))
            }
        }
    }
}

/** Skip back/forward and similar: rounded square on `surface`. */
@Composable
fun SquareButton(icon: ImageVector, description: String, size: Dp, onClick: () -> Unit, background: Color = LocalDabColors.current.surface,
                 tint: Color = LocalDabColors.current.neutral200, radius: Dp = 14.dp, iconScale: Float = 0.42f) {
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(background).pressable(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * iconScale))
    }
}

@Composable
fun HeartIcon(favourite: Boolean, size: Dp) {
    val c = LocalDabColors.current
    Icon(if (favourite) PhosphorIcons.Fill.Heart else PhosphorIcons.Regular.Heart,
        contentDescription = if (favourite) "Remove favourite" else "Add favourite",
        tint = if (favourite) Accent.pink else c.neutral500, modifier = Modifier.size(size))
}

/** Small, subtle signal indicator (replaces the LIVE badge). */
@Composable
fun SignalBars(bars: Int, height: Dp = 16.dp, modifier: Modifier = Modifier) {
    val c = LocalDabColors.current
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) Box(Modifier.width(height / 4).fillMaxHeight(0.25f * i)
            .background(if (i <= bars) c.neutral300 else c.neutral800, RoundedCornerShape(1.dp)))
    }
}

/** The orange waveform on the station that's playing (animated while audio plays). */
@Composable
fun Waveform(animate: Boolean, size: Dp) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(Modifier.size(size)) {
        val n = 5
        val w = this.size.width / (n * 2 - 1)
        for (i in 0 until n) {
            val base = floatArrayOf(0.45f, 0.8f, 1f, 0.65f, 0.4f)[i]
            val k = if (animate) (0.55f + 0.45f * kotlin.math.sin((phase * 2 * Math.PI + i * 1.3).toFloat())) else 1f
            val h = this.size.height * base * k
            drawRoundRect(Accent.orange, Offset(i * 2 * w, (this.size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

/** Segmented control (Display: Day/Auto/Night etc.): track `bg` with 6dp padding, options 120×64, radius 9. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, accent: Color, onAccentText: Color, onSelect: (T) -> Unit,
                  optionWidth: Dp = 120.dp, optionHeight: Dp = 64.dp, icons: Map<T, ImageVector> = emptyMap()) {
    val c = LocalDabColors.current
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(c.bg).padding(6.dp)) {
        for ((v, label) in options) {
            val sel = v == selected
            Row(
                Modifier.width(optionWidth).height(optionHeight).clip(RoundedCornerShape(9.dp))
                    .background(if (sel) accent else Color.Transparent).pressable({ onSelect(v) }),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                icons[v]?.let { Icon(it, null, tint = if (sel) onAccentText else c.neutral300, modifier = Modifier.size(22.dp).padding(end = 4.dp)) }
                Text(label, color = if (sel) onAccentText else c.neutral300, fontSize = 20.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Switch: 96×52, green track when on, 40dp knob #FFF5F0. */
@Composable
fun DabSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalDabColors.current
    val x = animateFloatAsState(if (on) 1f else 0f, tween(160), label = "knob")
    Box(Modifier.size(96.dp, 52.dp).clip(RoundedCornerShape(26.dp)).background(if (on) Accent.green else c.neutral800).pressable({ onChange(!on) })) {
        Box(Modifier.padding(6.dp).offset { IntOffset((44.dp.toPx() * x.value).roundToInt(), 0) }.size(40.dp).background(Accent.playIcon, CircleShape))
    }
}

/** Setting-row icon tile: role colour at 14% alpha, icon in the role colour. */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 64.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.5f))
    }
}

/** A plain fill-the-parent box, handy for placeholders. */
@Composable
fun Filler(color: Color) = Box(Modifier.fillMaxSize().background(color))
