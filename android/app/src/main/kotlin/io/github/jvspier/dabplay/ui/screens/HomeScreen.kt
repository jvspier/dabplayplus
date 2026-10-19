package io.github.jvspier.dabplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import android.os.SystemClock
import io.github.jvspier.dabplay.data.StationStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import io.github.jvspier.dabplay.data.ServiceKey
import kotlinx.coroutines.launch
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
import io.github.jvspier.dabplay.ui.UNSUPPORTED_DONGLE
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
                db.stations.isEmpty() && e.unsupportedDongle -> EmptyHint(UNSUPPORTED_DONGLE)
                db.stations.isEmpty() && !e.dongle -> EmptyHint("Connect the DAB dongle to find stations")
                db.stations.isEmpty() -> EmptyHint(e.scan?.let { "Scanning ${it.channel}…" } ?: "No stations yet: tap Scan on the Stations tab")
                else -> FavouritesGrid(favs, db.favourites, e, a, listTiles = compactTitle, onBrowse = onBrowse)
            }
        }
    }
}

/**
 * Favourites grid. Long-press a tile to pick it up, drag it over another slot (the others slide aside), release
 * to drop; the new order is saved. While dragging, the grid shows a local copy of the order. Held against the top or
 * bottom edge, the grid keeps scrolling (more than one page of favourites).
 */
@Composable
private fun FavouritesGrid(favs: List<Station>, favKeys: List<ServiceKey>, e: EngineState, a: RadioActions, listTiles: Boolean,
                           onBrowse: () -> Unit) {
    val cols = if (listTiles) 2 else 4
    val rows = if (listTiles) 3 else 2
    val gap = if (listTiles) 14.dp else 16.dp
    val pageSlots = cols * rows
    // the cap counts every favourite, also those Home can't show (not in the last scans)
    val addTiles = if (favs.size < pageSlots) pageSlots - favs.size else if (favKeys.size < StationStore.MAX_FAVOURITES) 1 else 0
    val order = remember { mutableStateListOf<Station>() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var edge by remember { mutableIntStateOf(0) }                // -1 / +1 while the finger is held at the top / bottom edge
    var droppedAt by remember { mutableLongStateOf(0L) }
    var settling by remember { mutableStateOf(false) }          // dropped; waiting for the store to catch up
    LaunchedEffect(favs) {
        settling = false
        // favourites changed under the finger (a heart in the mini player, a scan): the local order is stale, drop it
        if (dragKey != null && order.map { it.key }.toSet() != favs.map { it.key }.toSet()) { dragKey = null; edge = 0 }
    }
    val shown = if (dragKey != null || (settling && order.map { it.key } != favs.map { it.key })) order else favs
    val latestFavs by rememberUpdatedState(favs)
    // a long-press that picked a tile up (and didn't move) must not also count as a tap on it or its heart
    val tapOk = { dragKey == null && SystemClock.uptimeMillis() - droppedAt > 300 }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tileH = (maxHeight - gap * (rows - 1)) / rows
        val state = rememberLazyGridState()
        fun favAt(p: Offset) = state.layoutInfo.visibleItemsInfo.firstOrNull { i ->
            i.contentType == "fav" && p.x >= i.offset.x && p.x < i.offset.x + i.size.width &&
                p.y >= i.offset.y && p.y < i.offset.y + i.size.height
        }
        // the lifted tile's centre is over another tile: move it into that slot
        fun reorder() {
            val key = dragKey ?: return
            val cur = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
            val center = Offset(cur.offset.x + cur.size.width / 2f, cur.offset.y + cur.size.height / 2f) + dragOffset
            val target = favAt(center) ?: return
            if (target.key == key) return
            val from = order.indexOfFirst { it.key.toString() == key }
            val to = order.indexOfFirst { it.key.toString() == target.key }
            if (from < 0 || to < 0) return
            order.add(to, order.removeAt(from))
            // the tile now lives in the target's slot: keep it under the finger
            dragOffset += Offset((cur.offset.x - target.offset.x).toFloat(), (cur.offset.y - target.offset.y).toFloat())
        }
        val step = with(LocalDensity.current) { 10.dp.toPx() }
        LaunchedEffect(dragKey, edge) {
            if (dragKey == null || edge == 0) return@LaunchedEffect
            while (true) {
                val used = state.scrollBy(edge * step)
                if (used == 0f) break                               // at the end of the list
                dragOffset += Offset(0f, used)                      // the content moved, the finger didn't
                withFrameNanos { }
                reorder()
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols), state = state, flingBehavior = rememberSnapFlingBehavior(state),
            horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                val edgePx = 48.dp.toPx()
                detectDragGesturesAfterLongPress(
                    onDragStart = { p ->
                        val hit = favAt(p) ?: return@detectDragGesturesAfterLongPress
                        order.clear(); order.addAll(latestFavs)
                        settling = false
                        dragOffset = Offset.Zero
                        dragKey = hit.key as String
                    },
                    onDrag = { change, amount ->
                        if (dragKey == null) return@detectDragGesturesAfterLongPress
                        change.consume()
                        dragOffset += amount
                        reorder()
                        val y = change.position.y
                        edge = when { y < edgePx -> -1; y > size.height - edgePx -> 1; else -> 0 }
                    },
                    onDragEnd = {
                        val key = dragKey
                        if (key != null) {
                            val newIdx = order.indexOfFirst { it.key.toString() == key }
                            val oldIdx = latestFavs.indexOfFirst { it.key.toString() == key }
                            if (newIdx >= 0 && oldIdx >= 0 && newIdx != oldIdx) {
                                settling = true
                                // by key: to where the station it displaced is (hidden favourites keep their places)
                                a.moveFavourite(latestFavs[oldIdx].key, latestFavs[newIdx].key)
                            }
                        }
                        dragKey = null; edge = 0; droppedAt = SystemClock.uptimeMillis()
                    },
                    onDragCancel = { dragKey = null; edge = 0; droppedAt = SystemClock.uptimeMillis() },
                )
            },
        ) {
            items(shown, key = { it.key.toString() }, contentType = { "fav" }) { st ->
                val k = st.key.toString()
                val lifted = k == dragKey
                // head units don't vibrate: the pickup is visual. The tile pops up with a small springy overshoot
                // and a soft shadow; the other tiles dim a touch while one is being dragged.
                val scale = animateFloatAsState(if (lifted) 1.05f else 1f,
                    if (lifted) spring(dampingRatio = 0.45f, stiffness = 600f) else tween(150), label = "lift")
                val lift = animateFloatAsState(if (lifted) 1f else 0f, tween(150), label = "shadow")
                val dim = animateFloatAsState(if (dragKey != null && !lifted) 0.82f else 1f, tween(200), label = "dim")
                val m = if (lifted) Modifier.zIndex(1f).graphicsLayer {
                    translationX = dragOffset.x; translationY = dragOffset.y; scaleX = scale.value; scaleY = scale.value
                    shadowElevation = 18.dp.toPx() * lift.value; shape = RoundedCornerShape(14.dp); clip = false
                } else Modifier.animateItem().graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = dim.value }
                Box(m) {
                    val playing = st.key == e.current?.key
                    val play = { if (tapOk()) a.play(st.key) }
                    val heart = { if (tapOk()) a.toggleFavourite(st.key) }
                    if (listTiles) ListTile(st, playing, e.playing, tileH, play, heart) else GridTile(st, playing, e.playing, tileH, play, heart)
                }
            }
            items(addTiles, key = { "add$it" }, contentType = { "add" }) { AddTile(tileH, onBrowse) }
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
private fun GridTile(st: Station, isCurrent: Boolean, audio: Boolean, h: Dp, onPlay: () -> Unit, onHeart: () -> Unit) {
    val c = LocalDabColors.current
    Box(Modifier.fillMaxWidth().height(h).tileBackground(isCurrent).pressable(onPlay).padding(18.dp)) {
        StationLogo(st.label, st.key, if (h > 220.dp) 104.dp else 80.dp)
        // the heart gets a 64 dp touch target, drawn where the 30 dp icon was (17 dp of it reaching into the padding)
        Row(Modifier.align(Alignment.TopEnd).offset(x = 17.dp, y = (-17).dp), verticalAlignment = Alignment.CenterVertically) {
            if (isCurrent) Waveform(audio, 26.dp)             // the heart box's own margin is the gap
            Box(Modifier.size(64.dp).pressable(onHeart), contentAlignment = Alignment.Center) { HeartIcon(true, 30.dp) }
        }
        Text(st.label, color = if (isCurrent) c.liveSub else c.text, fontSize = 26.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 30.sp, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** 1b tile: logo 96, name 22/500, heart + waveform in a column on the right. */
@Composable
private fun ListTile(st: Station, isCurrent: Boolean, audio: Boolean, h: Dp, onPlay: () -> Unit, onHeart: () -> Unit) {
    val c = LocalDabColors.current
    Row(Modifier.fillMaxWidth().height(h).tileBackground(isCurrent).pressable(onPlay).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        StationLogo(st.label, st.key, minOf(96.dp, h - 28.dp))
        Text(st.label, color = if (isCurrent) c.liveSub else c.text, fontSize = 22.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).pressable(onHeart), contentAlignment = Alignment.Center) { HeartIcon(true, 26.dp) }
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
