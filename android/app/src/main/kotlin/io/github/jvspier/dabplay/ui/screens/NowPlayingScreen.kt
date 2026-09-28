package io.github.jvspier.dabplay.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

private val CARD_W = 560.dp
private val CARD_H = 420.dp          // 4:3 like DAB slides (320×240 at 1.75×)
private const val BAND = 0.25f       // the glass band: bottom quarter of the card
private const val LOGO = 0.55f       // logo tile size (of the card height) when there's no slide
private val FADE = 90.dp             // the ramp above the text band over which sharp turns frosted
private val LOGO_GAP = 12.dp         // the logo "stands" this far above the glass, its reflection smearing into it

/**
 * Now playing (owner's mockup, 2026-09-28): the slide or logo centred up top, a frosted-glass band over its bottom
 * quarter with the "Artist – Title" line on it (one font and size, scrolling when long), the controls centred
 * below, and the station (logo, name, signal) in the top corner away from the rail.
 */
@Composable
fun NowPlayingScreen(e: EngineState, db: Snapshot, a: RadioActions, chipOnLeft: Boolean) {
    val st = e.current
    Box(Modifier.fillMaxSize()) {
        if (st != null) StationChip(st, e, Modifier.align(if (chipOnLeft) Alignment.TopStart else Alignment.TopEnd).padding(top = 24.dp))
        Column(Modifier.fillMaxSize().padding(top = 36.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            NowCard(e, st)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Controls(e, db, a, st) }
        }
    }
}

/** What the glass band says, and in which colour. */
@Composable
private fun nowLine(e: EngineState, st: Station?): Pair<String, Color> {
    val c = LocalDabColors.current
    val np = e.nowPlaying
    val text = when {
        st == null -> "Nothing playing"
        !e.dongle && !e.playing -> "DAB dongle not connected"
        e.problem != null -> e.problem
        e.tuning -> "Tuning…"
        e.playing && e.reception == Reception.LOST -> noSignalText(e)
        np == null -> st.label
        else -> np.display                     // "Artist – Title" for songs, else what the station sends
    }
    return text to statusColor(e, c.text)
}

@Composable
private fun NowCard(e: EngineState, st: Station?) {
    val c = LocalDabColors.current
    val night = LocalNight.current
    val shape = RoundedCornerShape(18.dp)
    val slide = rememberSlide(e.slide)
    val logo = rememberLogo(st?.key)
    val blurred = rememberBlurred(slide ?: logo?.bitmap)
    val (line, lineColor) = nowLine(e, st)
    val tint = if (night) Color(0x8C12131F) else Color(0xA6FFFFFF)
    val band = CARD_H * BAND
    val frost = band + FADE                              // frosted area: the text band plus the ramp above it
    val tile = CARD_H * LOGO
    val tileTop = CARD_H * (1f - BAND) - LOGO_GAP - tile  // in card coordinates
    Box(Modifier.size(CARD_W, CARD_H)
        .shadow(40.dp, shape, ambientColor = Accent.orange.copy(alpha = 0.35f), spotColor = Accent.orange.copy(alpha = 0.35f))
        .clip(shape).background(c.surface)) {
        // the picture, sharp: the slide, whole (4:3 in 4:3), or the station logo standing just above the text band
        Crossfade(slide, animationSpec = tween(300), label = "art") { b ->
            if (b != null) Image(b, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxWidth().fillMaxHeight(1f - BAND).padding(bottom = LOGO_GAP), contentAlignment = Alignment.BottomCenter) {
                if (st != null) StationLogo(st.label, st.key, tile, radius = 22.dp)
            }
        }
        // the glass: the blurred copy plus a tint, faded in over [FADE] so sharp turns frosted with no edge
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(frost).clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, FADE / frost to Color.Black), blendMode = BlendMode.DstIn)
            }) {
            val top = CARD_H - frost                          // this box's top, in card coordinates
            if (blurred != null) {
                if (slide != null) Image(blurred, null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High,
                    modifier = Modifier.requiredSize(CARD_W, CARD_H).align(Alignment.BottomCenter))
                else {
                    // the logo's bottom goes soft, and its reflection smears on into the glass below it
                    Image(blurred, null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = tileTop - top).requiredSize(tile))
                    Image(blurred, null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = tileTop + tile - top).requiredSize(tile)
                            .graphicsLayer { scaleY = -1f; alpha = 0.4f; compositingStrategy = CompositingStrategy.Offscreen }   // soft: the text sits on it
                            .drawWithContent {
                                drawContent()
                                // drawn before the mirroring: the logo's bottom edge (opaque) is this box's bottom
                                drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black), blendMode = BlendMode.DstIn)
                            })
                }
            }
            Box(Modifier.fillMaxSize().background(tint))
        }
        Text(line, color = lineColor, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.BottomCenter).height(band).wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = 28.dp)
                .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500, repeatDelayMillis = 3000))
    }
}

@Composable
private fun StationChip(st: Station, e: EngineState, modifier: Modifier) {
    val c = LocalDabColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StationLogo(st.label, st.key, 44.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(st.label, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.playing) SignalBars(e.signalBars, 14.dp, Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Controls(e: EngineState, db: Snapshot, a: RadioActions, st: Station?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        SkipButton(true) { a.skip(-1) }
        PlayButton(e.playing, 136.dp, a.togglePlay, busy = e.tuning)
        SkipButton(false) { a.skip(+1) }
        if (st != null) Box(Modifier.size(104.dp).background(Accent.pink.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .pressable({ a.toggleFavourite(st.key) }), contentAlignment = Alignment.Center) { HeartIcon(st.key in db.favourites, 44.dp) }
        else Spacer(Modifier.size(104.dp))
    }
}

@Composable
private fun SkipButton(back: Boolean, onClick: () -> Unit) {
    SquareButton(if (back) PhosphorIcons.Fill.SkipBack else PhosphorIcons.Fill.SkipForward, if (back) "Previous" else "Next", 104.dp, onClick, radius = 16.dp)
}
