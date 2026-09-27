package io.github.jvspier.dabplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
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
import io.github.jvspier.dabplay.engine.EngineState
import io.github.jvspier.dabplay.ui.Artwork
import io.github.jvspier.dabplay.ui.noSignalText
import io.github.jvspier.dabplay.ui.statusColor
import io.github.jvspier.dabplay.ui.RadioActions
import io.github.jvspier.dabplay.ui.components.HeartIcon
import io.github.jvspier.dabplay.ui.components.PlayButton
import io.github.jvspier.dabplay.ui.components.SignalBars
import io.github.jvspier.dabplay.ui.components.StationLogo
import io.github.jvspier.dabplay.ui.components.pressable
import io.github.jvspier.dabplay.ui.theme.Accent
import io.github.jvspier.dabplay.ui.theme.LocalDabColors

/** Now playing: 4:3 artwork (slide or logo) left; station, "Artist – Title" and controls right. */
@Composable
fun NowPlayingScreen(e: EngineState, db: Snapshot, a: RadioActions, large: Boolean) {
    val c = LocalDabColors.current
    val st = e.current
    val np = e.nowPlaying
    val artW = if (large) 520.dp else 552.dp
    val artH = artW * 3 / 4
    // the npGlow background is drawn full-screen by AppShell, so it isn't clipped by the content padding
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(e.slide, st?.label ?: "", st?.key, artW, artH,
                modifier = Modifier.shadow(40.dp, RoundedCornerShape(18.dp), ambientColor = Accent.orange.copy(alpha = 0.35f), spotColor = Accent.orange.copy(alpha = 0.35f)))
            Spacer(Modifier.width(if (large) 44.dp else 36.dp))
            Column(Modifier.width(if (large) 520.dp else 450.dp)) {
                if (st != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    StationLogo(st.label, st.key, 48.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(st.label, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (e.playing) SignalBars(e.signalBars, 14.dp, Modifier.padding(top = 4.dp))
                    }
                }
                Spacer(Modifier.height(20.dp))
                val (title, artist) = when {
                    st == null -> "Nothing playing" to "Pick a station on Home or Stations"
                    e.problem != null -> st.label to e.problem
                    e.tuning -> st.label to "Tuning…"
                    e.playing && e.reception == Reception.LOST -> st.label to noSignalText(e)
                    np == null -> st.label to ""
                    np.isSong -> np.title!! to np.artist!!
                    else -> np.raw to ""
                }
                val long = title.length > 28
                Text(title, color = c.text, fontSize = if (long) 40.sp else 52.sp, fontWeight = FontWeight.Medium, lineHeight = if (long) 44.sp else 55.sp,
                    letterSpacing = (-0.5).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (artist.isNotEmpty()) Text(artist, color = statusColor(e, c.neutral300), fontSize = 28.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(38.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (large) 20.dp else 12.dp)) {
                    val side = if (large) 104.dp else 88.dp
                    SkipButton(true, side) { a.skip(-1) }
                    PlayButton(e.playing, if (large) 136.dp else 128.dp, a.togglePlay, busy = e.tuning)
                    SkipButton(false, side) { a.skip(+1) }
                    if (st != null) {
                        val fav = st.key in db.favourites
                        Box(Modifier.size(side).background(Accent.pink.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                            .pressable({ a.toggleFavourite(st.key) }), contentAlignment = Alignment.Center) { HeartIcon(fav, 44.dp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkipButton(back: Boolean, size: Dp, onClick: () -> Unit) {
    io.github.jvspier.dabplay.ui.components.SquareButton(if (back) PhosphorIcons.Fill.SkipBack else PhosphorIcons.Fill.SkipForward,
        if (back) "Previous" else "Next", size, onClick, radius = 16.dp)
}
