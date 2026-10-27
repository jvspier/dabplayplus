package io.github.jvspier.dabplay.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import io.github.jvspier.dabplay.core.Reception
import io.github.jvspier.dabplay.data.Snapshot
import io.github.jvspier.dabplay.data.Station
import io.github.jvspier.dabplay.engine.EngineState
import io.github.jvspier.dabplay.ui.NOT_RESPONDING
import io.github.jvspier.dabplay.ui.RadioActions
import io.github.jvspier.dabplay.ui.noSignalText
import io.github.jvspier.dabplay.ui.statusColor
import io.github.jvspier.dabplay.ui.components.HeartIcon
import io.github.jvspier.dabplay.ui.components.PlayButton
import io.github.jvspier.dabplay.ui.components.SignalBars
import io.github.jvspier.dabplay.ui.components.SquareButton
import io.github.jvspier.dabplay.ui.components.StationLogo
import io.github.jvspier.dabplay.ui.components.pressable
import io.github.jvspier.dabplay.ui.components.rememberBlurred
import io.github.jvspier.dabplay.ui.components.rememberLogo
import io.github.jvspier.dabplay.ui.components.rememberSlide
import io.github.jvspier.dabplay.ui.theme.Accent
import io.github.jvspier.dabplay.ui.theme.LocalDabColors
import io.github.jvspier.dabplay.ui.theme.LocalNight

private val CARD_MAX_H = 480.dp      // 4:3, 640×480 = DAB's 320×240 slides at exactly 2× (sharpest scaling)
private val CONTROLS_H = 136.dp
private const val BAND = 0.25f       // the glass band: bottom quarter of the card
private const val LOGO = 0.62f       // logo tile size (of the card height) when there's no slide
private val REFLECTION = 110.dp       // how far below the picture its reflection fades out
private val LOGO_GAP = 12.dp         // the logo "stands" this far above the glass, its reflection smearing into it

/**
 * Now playing (owner's mockup, 2026-09-28): the slide or logo centred up top, a frosted-glass band over its bottom
 * quarter with the "Artist – Title" line on it (one font and size, scrolling when long), the controls centred
 * below, and the station (logo, name, signal) in the top corner away from the rail.
 */
@Composable
fun NowPlayingScreen(e: EngineState, db: Snapshot, a: RadioActions, chipOnLeft: Boolean, taOn: Boolean = false) {
    val st = e.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // as big as fits (up to 640×480), leaving room for the controls below and the station chip beside it
        val cardH = minOf(CARD_MAX_H, maxHeight - 28.dp - CONTROLS_H - 56.dp, (maxWidth - 400.dp) * 3 / 4).coerceAtLeast(0.dp)
        if (st != null) {
            // station chip in one top corner, the favourite heart in the other, level with it (owner, 2026-09-28):
            // symmetric, and on the driver's side (the rail's side) the heart is closest to hand
            StationChip(st, e, taOn, Modifier.align(if (chipOnLeft) Alignment.TopStart else Alignment.TopEnd).padding(top = 24.dp))
            // the content has 8 dp next to the rail and 28 dp on the far side: +20 dp so both corners are inset alike.
            // Not during an announcement: the station shown then is the traffic service, not yours.
            if (e.announcement == null) Box(Modifier.align(if (chipOnLeft) Alignment.TopEnd else Alignment.TopStart)
                .padding(top = 22.dp, start = if (chipOnLeft) 0.dp else 20.dp, end = if (chipOnLeft) 20.dp else 0.dp).size(48.dp)
                .background(Accent.pink.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                .pressable({ a.toggleFavourite(st.key) }), contentAlignment = Alignment.Center) { HeartIcon(st.key in db.favourites, 24.dp) }
        }
        Column(Modifier.fillMaxSize().padding(top = 28.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // the line spans the whole width, not just the picture's (owner, 2026-10-27: long lines scrolled in a narrow strip)
            Box(Modifier.fillMaxWidth().height(cardH)) {
                NowCard(e, st, cardH, Modifier.align(Alignment.TopCenter))
                NowLine(e, st, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(cardH * BAND).padding(horizontal = 40.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Controls(e, a) }
        }
    }
}

/** What the glass band says, and in which colour. */
@Composable
private fun nowLine(e: EngineState, st: Station?): Pair<String, Color> {
    val c = LocalDabColors.current
    val np = e.nowPlaying
    val problem = e.problem                    // a local: EngineState lives in another module (no smart cast)
    val text = when {
        st == null -> "Nothing playing"
        e.unsupportedDongle -> "USB device not supported"
        !e.dongle && e.notResponding -> NOT_RESPONDING
        !e.dongle && !e.playing -> "DAB dongle not connected"
        problem != null -> problem
        e.tuning -> "Tuning…"
        e.playing && e.reception == Reception.LOST -> noSignalText(e)
        np == null -> st.label
        else -> np.display                     // "Artist – Title" for songs, else what the station sends
    }
    return text to statusColor(e, c.text)
}

/**
 * The artwork "stage" (owner, 2026-09-28): slide or logo, sharp and whole, standing on a floor above the text line
 * ([NowLine]), with its blurred reflection fading out below. Same treatment for both: no card, no glass over the
 * picture. A slide stands as tall as the floor allows (≈1.45× its 320×240), a logo at [LOGO].
 */
@Composable
private fun NowCard(e: EngineState, st: Station?, CARD_H: Dp, modifier: Modifier = Modifier) {
    val CARD_W = CARD_H * 4 / 3
    val night = LocalNight.current
    val slide = rememberSlide(e.slide, st?.key)
    val logo = rememberLogo(st?.key)
    val floor = CARD_H * (1f - BAND) - LOGO_GAP          // where the picture stands, from the top
    val tile = CARD_H * LOGO
    val glow = Accent.orange.copy(alpha = 0.3f)
    Box(modifier.size(CARD_W, CARD_H).clipToBounds()) {             // the reflection never reaches the controls
        Crossfade(slide, Modifier.fillMaxSize(), animationSpec = tween(300), label = "art") { b ->
            Box(Modifier.fillMaxSize()) {
                // each crossfade child reflects its own picture: the outgoing slide keeps its reflection while fading
                val reflection = rememberBlurred(b ?: logo?.bitmap)
                val w = if (b != null) floor * 4 / 3 else tile
                val h = if (b != null) floor else tile
                val radius = if (b != null) 18.dp else 22.dp
                val at = Modifier.align(Alignment.TopCenter).offset(y = floor - h).size(w, h)
                    .shadow(28.dp, RoundedCornerShape(radius), ambientColor = glow, spotColor = glow)
                if (b != null) Image(b, contentDescription = null, contentScale = ContentScale.Fit, modifier = at.clip(RoundedCornerShape(radius)))
                else if (st != null) StationLogo(st.label, st.key, tile, radius = radius, modifier = at)
                // by day, a faint neutral sheen under every picture: a white-bottomed slide (NPO's cards) reflects white on
                // a light page, which is invisible, so this keeps the "stand" visible (owner, 2026-09-28)
                if (!night && (b != null || st != null)) Box(Modifier.align(Alignment.TopCenter).offset(y = floor).size(w, REFLECTION)
                    .clip(RoundedCornerShape(topStart = radius, topEnd = radius))
                    .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.07f), 1f to Color.Transparent)))
                // the reflection: mirrored right under the picture, blurred, fading out; soft, the text sits on it
                reflection?.let { r ->
                    Image(r, null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = floor).requiredSize(w, h)
                            // fainter by day, where a dark picture's reflection would show as a grey block
                            // (slides a bit fainter than logos: a bright slide would reflect as a second card)
                            .graphicsLayer {
                                scaleY = -1f; compositingStrategy = CompositingStrategy.Offscreen
                                alpha = when { b != null && night -> 0.22f; b != null -> 0.15f; night -> 0.4f; else -> 0.25f }
                            }
                            .clip(RoundedCornerShape(radius))
                            .drawWithContent {
                                drawContent()
                                // drawn before the mirroring: the picture's bottom edge (opaque) is this box's bottom;
                                // fades out over REFLECTION, whatever the picture's height
                                val fade = (REFLECTION.toPx() / size.height).coerceAtMost(1f)
                                drawRect(Brush.verticalGradient(1f - fade to Color.Transparent, 1f to Color.Black), blendMode = BlendMode.DstIn)
                            })
                }
            }
        }
    }
}

/** The "Artist – Title" line under the picture: centred, scrolling when it's wider than [modifier] allows. */
@Composable
private fun NowLine(e: EngineState, st: Station?, modifier: Modifier) {
    val night = LocalNight.current
    val (line, lineColor) = nowLine(e, st)
    // a subtle shadow keeps the line readable over any reflection (owner, 2026-09-28): dark at night, a light halo by day
    val shadow = if (night) Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), blurRadius = 10f)
                 else Shadow(Color.White.copy(alpha = 0.7f), Offset(0f, 1f), blurRadius = 8f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(line, color = lineColor, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip,
            style = LocalTextStyle.current.copy(shadow = shadow),
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500, repeatDelayMillis = 3000))
    }
}

/** Station logo, name and signal. */
@Composable
private fun StationChip(st: Station, e: EngineState, taOn: Boolean, modifier: Modifier) {
    val c = LocalDabColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StationLogo(st.label, st.key, 44.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(st.label, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (e.playing) SignalBars(e.signalBars, 14.dp)
                if (taOn) TaBadge(e, Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** "TA", as on car radios: lit when the station takes part in traffic announcements, amber while one plays. */
@Composable
private fun TaBadge(e: EngineState, modifier: Modifier) {
    val c = LocalDabColors.current
    val on = e.announcement != null
    val ink = when { on -> Color(0xFF2A1A00); e.taSupported -> c.text; else -> c.neutral500.copy(alpha = 0.6f) }
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(if (on) Accent.amber else Color.Transparent)
        .border(1.5.dp, if (on) Accent.amber else ink, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text("TA", color = ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp)
    }
}

/** Previous, play/pause, next: a symmetric group, centred. */
@Composable
private fun Controls(e: EngineState, a: RadioActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        SkipButton(true) { a.skip(-1) }
        PlayButton(e.playing, 136.dp, a.togglePlay, busy = e.tuning)
        SkipButton(false) { a.skip(+1) }
    }
}

@Composable
private fun SkipButton(back: Boolean, onClick: () -> Unit) {
    SquareButton(if (back) PhosphorIcons.Fill.SkipBack else PhosphorIcons.Fill.SkipForward, if (back) "Previous" else "Next", 104.dp, onClick, radius = 16.dp)
}
