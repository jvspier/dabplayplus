package io.github.jvspier.dabplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Plus
import io.github.jvspier.dabplay.data.Snapshot
import io.github.jvspier.dabplay.data.Station
import io.github.jvspier.dabplay.engine.EngineState
import io.github.jvspier.dabplay.ui.EmptyHint
import io.github.jvspier.dabplay.ui.RadioActions
import io.github.jvspier.dabplay.ui.ScreenTitle
import io.github.jvspier.dabplay.ui.components.HeartIcon
import io.github.jvspier.dabplay.ui.components.StationLogo
import io.github.jvspier.dabplay.ui.components.Waveform
import io.github.jvspier.dabplay.ui.components.pressable
import io.github.jvspier.dabplay.ui.theme.Accent
import io.github.jvspier.dabplay.ui.theme.LocalDabColors

/** Favourites: the first page (8 tiles in 1a, 6 in 1b) needs no scrolling; more favourites scroll with row snapping. */
@Composable
fun HomeScreen(e: EngineState, db: Snapshot, a: RadioActions, compactTitle: Boolean, onBrowse: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Favorites", compactTitle)
        val favs = db.favouriteStations
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                db.stations.isEmpty() && !e.dongle -> EmptyHint("Connect the DAB dongle to find stations")
                db.stations.isEmpty() -> EmptyHint(e.scan?.let { "Scanning ${it.channel}…" } ?: "No stations yet: tap Scan on the Stations tab")
                else -> FavouritesGrid(favs, e, a, listTiles = compactTitle, onBrowse = onBrowse)
            }
        }
    }
}

@Composable
private fun FavouritesGrid(favs: List<Station>, e: EngineState, a: RadioActions, listTiles: Boolean, onBrowse: () -> Unit) {
    val cols = if (listTiles) 2 else 4
    val rows = if (listTiles) 3 else 2
    val gap = if (listTiles) 14.dp else 16.dp
    val pageSlots = cols * rows
    val addTiles = if (favs.size < pageSlots) pageSlots - favs.size else if (favs.size < 24) 1 else 0
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tileH = (maxHeight - gap * (rows - 1)) / rows
        val state = rememberLazyGridState()
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols), state = state, flingBehavior = rememberSnapFlingBehavior(state),
            horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(favs, key = { it.key.toString() }) { st ->
                val playing = st.key == e.current?.key
                if (listTiles) ListTile(st, playing, e.playing, tileH, a) else GridTile(st, playing, e.playing, tileH, a)
            }
            items(addTiles, key = { "add$it" }) { AddTile(tileH, onBrowse) }
        }
    }
}

@Composable
private fun Modifier.tileBackground(playing: Boolean): Modifier {
    val c = LocalDabColors.current
    val shape = RoundedCornerShape(14.dp)
    return if (playing) this.shadow(18.dp, shape, ambientColor = Accent.orange, spotColor = Accent.orange)
        .clip(shape).background(Brush.linearGradient(listOf(c.liveTileFrom, c.liveTileTo))).border(2.dp, Accent.orange, shape)
    else this.clip(shape).background(c.surface).border(1.dp, c.neutral800.copy(alpha = 0.6f), shape)
}

/** 1a tile: logo top-left, heart (+ waveform when playing) top-right, name 26/500 at the bottom. */
@Composable
private fun GridTile(st: Station, isCurrent: Boolean, audio: Boolean, h: Dp, a: RadioActions) {
    val c = LocalDabColors.current
    Box(Modifier.fillMaxWidth().height(h).tileBackground(isCurrent).pressable({ a.play(st.key) }).padding(18.dp)) {
        StationLogo(st.label, st.key, if (h > 220.dp) 104.dp else 80.dp)
        Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
            if (isCurrent) { Waveform(audio, 26.dp); Spacer(Modifier.width(10.dp)) }
            Box(Modifier.pressable({ a.toggleFavourite(st.key) })) { HeartIcon(true, 30.dp) }
        }
        Text(st.label, color = if (isCurrent) c.liveSub else c.text, fontSize = 26.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 30.sp, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** 1b tile: logo 96, name 22/500, heart + waveform in a column on the right. */
@Composable
private fun ListTile(st: Station, isCurrent: Boolean, audio: Boolean, h: Dp, a: RadioActions) {
    val c = LocalDabColors.current
    Row(Modifier.fillMaxWidth().height(h).tileBackground(isCurrent).pressable({ a.play(st.key) }).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        StationLogo(st.label, st.key, minOf(96.dp, h - 28.dp))
        Text(st.label, color = if (isCurrent) c.liveSub else c.text, fontSize = 22.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.pressable({ a.toggleFavourite(st.key) })) { HeartIcon(true, 26.dp) }
            if (isCurrent) Waveform(audio, 24.dp)
        }
    }
}

@Composable
private fun AddTile(h: Dp, onBrowse: () -> Unit) {
    val c = LocalDabColors.current
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().height(h).clip(shape).border(2.dp, c.neutral800, shape).pressable(onBrowse),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(PhosphorIcons.Regular.Plus, "Add favourite", tint = c.neutral500, modifier = Modifier.size(40.dp))
        Text("Add", color = c.neutral500, fontSize = 20.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
