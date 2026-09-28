package io.github.jvspier.dabplay.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Broadcast
import com.adamglin.phosphoricons.fill.CircleNotch
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.HeartBreak
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val LETTERS = listOf("#") + ('A'..'Z').map { it.toString() }
private val SPRING_BACK = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

private fun groupOf(label: String): String {
    val ch = label.trimStart().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (ch in 'A'..'Z') ch.toString() else "#"
}

/** List rows: a sticky letter header or a station. Indices into this list are what the scrubber scrolls to. */
private sealed interface Row_ { val key: String }
private data class Header(val letter: String) : Row_ { override val key get() = "h$letter" }
private data class Item(val station: Station) : Row_ { override val key get() = station.key.toString() }

@Composable
fun StationsScreen(e: EngineState, db: Snapshot, a: RadioActions, compactTitle: Boolean) {
    val stations = db.stations
    // grouping only reruns when the station list itself changes, not on every engine update
    val (rows, letterIndex) = remember(stations) {
        val out = mutableListOf<Row_>()
        val idx = mutableMapOf<String, Int>()
        stations.groupBy { groupOf(it.label) }.toSortedMap(compareBy { LETTERS.indexOf(it) }).forEach { (letter, list) ->
            idx[letter] = out.size; out += Header(letter); list.forEach { out += Item(it) }
        }
        out to idx
    }
    val favSet = remember(db.favourites) { db.favourites.toSet() }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("All stations", compactTitle, extra = if (stations.isNotEmpty()) "${stations.size}" else null,
            trailing = { ScanButton(e, a) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (stations.isEmpty()) {
                EmptyHint(if (!e.dongle) "Connect the DAB dongle, then tap Scan" else if (e.scan != null) "Looking for stations…" else "Tap Scan to find stations")
                return@Box
            }
            val list = rememberLazyListState()
            var scrub by remember { mutableStateOf<String?>(null) }
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight().fadeEdges(), state = list, contentPadding = PaddingValues(vertical = 12.dp)) {
                    rows.forEach { r ->
                        when (r) {
                            is Header -> stickyHeader(key = r.key, contentType = "h") { LetterHeader(r.letter) }
                            is Item -> item(key = r.key, contentType = "s") {
                                StationRow(r.station, r.station.key == e.current?.key, e.playing, r.station.key in favSet, a)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Scrubber(letterIndex, list, scrub) { scrub = it }
            }
            ScrubBubble(scrub)
        }
    }
}

/** Soft top/bottom fade (mask), like the design; drawn in an offscreen layer so only the list is faded. */
private fun Modifier.fadeEdges() = this.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
    drawContent()
    val f = 28.dp.toPx() / size.height
    drawRect(Brush.verticalGradient(0f to Color.Transparent, f to Color.Black, 1f - f to Color.Black, 1f to Color.Transparent),
        blendMode = BlendMode.DstIn)
}

@Composable
private fun ScanButton(e: EngineState, a: RadioActions) {
    val scanning = e.scan != null
    Row(Modifier.height(64.dp).clip(RoundedCornerShape(14.dp)).background(Accent.blue).pressable(a.scan, enabled = e.dongle && !scanning)
        .padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        val ink = Color(0xFF0B1733)
        if (scanning) {
            val t = rememberInfiniteTransition(label = "scan")
            val angle = t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
            Icon(PhosphorIcons.Fill.CircleNotch, null, tint = ink, modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = angle.value })
        } else Icon(PhosphorIcons.Fill.Broadcast, null, tint = ink, modifier = Modifier.size(28.dp))
        Text(if (scanning) "Scanning ${e.scan!!.channel}…" else "Scan", color = ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun LetterHeader(letter: String) {
    val c = LocalDabColors.current
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(c.sticky).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart) {
        Text(letter, color = c.az, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * One station, 84dp. Tap plays; the heart toggles the favourite; swiping right (max 200dp, threshold 120dp)
 * reveals a pink strip and toggles the favourite on release, then springs back.
 */
@Composable
private fun StationRow(st: Station, isCurrent: Boolean, audio: Boolean, fav: Boolean, a: RadioActions) {
    val c = LocalDabColors.current
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val key = st.key
    Box(Modifier.fillMaxWidth().height(84.dp)) {
        if (dx.value > 0f) {
            val armed = with(LocalDensity.current) { dx.value >= 120.dp.toPx() }
            Row(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Accent.pink.copy(alpha = 0.22f)).padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (fav) PhosphorIcons.Fill.HeartBreak else PhosphorIcons.Fill.Heart, null, tint = c.pinkInk, modifier = Modifier.size(30.dp))
                Text(if (fav) "Remove" else "Favorite", color = c.pinkInk, fontSize = 20.sp, fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.padding(start = 10.dp))
            }
        }
        Row(
            Modifier.fillMaxSize().offset { IntOffset(dx.value.roundToInt(), 0) }.background(c.bg)
                .swipeToFavourite(dx, onCommit = { a.toggleFavourite(key) }, scope = scope)
                .pressable({ a.play(key) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StationLogo(st.label, key, 60.dp)
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 18.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(st.label, color = if (isCurrent) c.liveSub else c.text, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (isCurrent) { Spacer(Modifier.width(14.dp)); Waveform(audio, 24.dp) }
                    }
                    Box(Modifier.size(64.dp).pressable({ a.toggleFavourite(key) }), contentAlignment = Alignment.Center) { HeartIcon(fav, 30.dp) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.neutral800.copy(alpha = 0.6f)))
            }
        }
    }
}

private fun Modifier.swipeToFavourite(dx: Animatable<Float, *>, onCommit: () -> Unit, scope: kotlinx.coroutines.CoroutineScope) =
    this.pointerInput(Unit) {
        val max = 200.dp.toPx()
        val threshold = 120.dp.toPx()
        detectHorizontalDragGestures(
            onDragEnd = {
                if (dx.value >= threshold) onCommit()
                scope.launch { dx.animateTo(0f, tween(300, easing = SPRING_BACK)) }
            },
            onDragCancel = { scope.launch { dx.animateTo(0f, tween(300, easing = SPRING_BACK)) } },
        ) { change, amount ->
            change.consume()
            scope.launch { dx.snapTo((dx.value + amount).coerceIn(0f, max)) }
        }
    }

/** A–Z scrubber: a 52dp pill; touch or drag to jump; letters with no stations are dimmed. Reports the touched letter for the bubble. */
@Composable
private fun Scrubber(letterIndex: Map<String, Int>, list: LazyListState, active: String?, onActive: (String?) -> Unit) {
    val c = LocalDabColors.current
    val scope = rememberCoroutineScope()
    val index by rememberUpdatedState(letterIndex)
    BoxWithConstraints(Modifier.width(52.dp).fillMaxHeight().clip(RoundedCornerShape(26.dp)).background(c.surface).padding(vertical = 8.dp)) {
        // every letter gets an equal slot, so the finger position maps 1:1 to what's drawn, whatever the screen height
        val slot = maxHeight / LETTERS.size
        val font = with(LocalDensity.current) { minOf(15.sp.toPx(), (slot * 0.78f).toPx()).toSp() }
        Column(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var scrolledTo: String? = null
                fun pick(y: Float) {
                    val i = (y / size.height * LETTERS.size).toInt().coerceIn(0, LETTERS.size - 1)
                    // nearest letter at or after the finger that has stations, else the last one before it
                    val l = (i until LETTERS.size).map { LETTERS[it] }.firstOrNull { it in index }
                        ?: (i downTo 0).map { LETTERS[it] }.firstOrNull { it in index } ?: return
                    onActive(LETTERS[i])
                    if (l != scrolledTo) { scrolledTo = l; scope.launch { list.scrollToItem(index.getValue(l)) } }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume(); pick(down.position.y)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume(); pick(ch.position.y)
                    }
                    scrolledTo = null
                    onActive(null)
                }
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (l in LETTERS) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(l, color = if (l in letterIndex) c.neutral300 else c.neutral700, fontSize = font, lineHeight = font,
                    fontWeight = if (l == active) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

/** The big amber letter while scrubbing. */
@Composable
private fun BoxScope.ScrubBubble(active: String?) {
    var last by remember { mutableStateOf("") }
    if (active != null) last = active
    AnimatedVisibility(active != null, Modifier.align(Alignment.Center),
        enter = fadeIn(tween(120)) + scaleIn(tween(120), 0.85f), exit = fadeOut(tween(180)) + scaleOut(tween(180), 0.85f)) {
        Box(Modifier.size(120.dp).clip(RoundedCornerShape(24.dp)).background(Accent.amber), contentAlignment = Alignment.Center) {
            Text(last, color = Color(0xFF2A1A00), fontSize = 64.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
