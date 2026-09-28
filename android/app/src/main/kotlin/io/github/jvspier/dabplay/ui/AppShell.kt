package io.github.jvspier.dabplay.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.ClockCounterClockwise
import com.adamglin.phosphoricons.fill.GearSix
import com.adamglin.phosphoricons.fill.House
import com.adamglin.phosphoricons.fill.Radio
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.fill.Waveform
import io.github.jvspier.dabplay.core.Reception
import io.github.jvspier.dabplay.data.DriverSide
import io.github.jvspier.dabplay.data.LayoutVariant
import io.github.jvspier.dabplay.data.Recent
import io.github.jvspier.dabplay.data.ServiceKey
import io.github.jvspier.dabplay.data.Settings
import io.github.jvspier.dabplay.data.Snapshot
import io.github.jvspier.dabplay.engine.EngineState
import io.github.jvspier.dabplay.logos.LogoStatus
import io.github.jvspier.dabplay.ui.components.PlayButton
import io.github.jvspier.dabplay.ui.components.SignalBars
import io.github.jvspier.dabplay.ui.components.SquareButton
import io.github.jvspier.dabplay.ui.components.StationLogo
import io.github.jvspier.dabplay.ui.components.pressable
import io.github.jvspier.dabplay.ui.components.rememberSlide
import io.github.jvspier.dabplay.ui.screens.HomeScreen
import io.github.jvspier.dabplay.ui.screens.NowPlayingScreen
import io.github.jvspier.dabplay.ui.screens.RecentScreen
import io.github.jvspier.dabplay.ui.screens.SettingsScreen
import io.github.jvspier.dabplay.ui.screens.StationsScreen
import io.github.jvspier.dabplay.ui.theme.Accent
import io.github.jvspier.dabplay.ui.theme.LocalDabColors
import io.github.jvspier.dabplay.ui.theme.LocalNight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class Tab(val label: String, val color: Color, val icon: ImageVector) {
    HOME("Home", Accent.blue, PhosphorIcons.Fill.House),
    STATIONS("Stations", Accent.amber, PhosphorIcons.Fill.Radio),
    PLAYING("Playing", Accent.orange, PhosphorIcons.Fill.Waveform),
    RECENT("Recent", Accent.green, PhosphorIcons.Fill.ClockCounterClockwise),
    SETTINGS("Settings", Accent.lavender, PhosphorIcons.Fill.GearSix),
}

/** Everything the UI can ask the radio to do. */
class RadioActions(
    val play: (ServiceKey) -> Unit,
    val togglePlay: () -> Unit,
    val skip: (Int) -> Unit,
    val toggleFavourite: (ServiceKey) -> Unit,
    val moveFavourite: (Int, Int) -> Unit,
    val scan: () -> Unit,
    val clearRecents: () -> List<Recent>,
    val restoreRecents: (List<Recent>) -> Unit,
    val updateSettings: ((Settings) -> Settings) -> Unit,
)

@Composable
fun AppShell(engine: EngineState, db: Snapshot, settings: Settings, actions: RadioActions, logoStatus: LogoStatus = LogoStatus()) {
    val c = LocalDabColors.current
    var tab by rememberSaveable { mutableStateOf(if (db.favourites.isEmpty() && db.stations.isNotEmpty()) Tab.STATIONS else Tab.HOME) }
    val saved = rememberSaveableStateHolder()
    val panelLayout = settings.layout == LayoutVariant.PANEL
    val showPanel = panelLayout && tab != Tab.PLAYING
    val showBar = !panelLayout && tab != Tab.PLAYING
    val right = settings.driverSide == DriverSide.RIGHT

    // the Now-playing glow fades in over the whole screen (not just the content column), 300ms
    val npGlow by animateFloatAsState(if (tab == Tab.PLAYING) 1f else 0f, tween(300), label = "npGlow")
    CompositionLocalProvider(LocalClockFormat provides settings.clock) {
    Box(Modifier.fillMaxSize().background(c.bg).pageGlow().drawBehind {
        if (npGlow > 0f) drawRect(Brush.radialGradient(listOf(c.npGlow, Color.Transparent), center = Offset(size.width * 0.45f, size.height * 0.5f),
            radius = size.width * 0.6f), alpha = npGlow)
    }) {
        Row(Modifier.fillMaxSize()) {
            val parts = buildList<@Composable RowScope.() -> Unit> {
                add { Rail(tab, right) { tab = it } }
                if (showPanel) add { PlayerPanel(engine, db, actions) { tab = Tab.PLAYING } }
                add {
                    Column(Modifier.weight(1f).fillMaxHeight().padding(start = if (!right && !showPanel) 8.dp else 28.dp, end = if (right && !showPanel) 8.dp else 28.dp)) {
                        // short crossfade between tabs; each tab keeps its scroll position while you're elsewhere
                        Crossfade(tab, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(180), label = "tab") { t ->
                            saved.SaveableStateProvider(t.name) {
                                when (t) {
                                    Tab.HOME -> HomeScreen(engine, db, actions, compactTitle = panelLayout, onBrowse = { tab = Tab.STATIONS })
                                    Tab.STATIONS -> StationsScreen(engine, db, actions, compactTitle = panelLayout)
                                    Tab.PLAYING -> NowPlayingScreen(engine, db, actions, chipOnLeft = right)
                                    Tab.RECENT -> RecentScreen(engine, db, actions, compactTitle = panelLayout)
                                    Tab.SETTINGS -> SettingsScreen(settings, db, actions, compactTitle = panelLayout, logoStatus = logoStatus)
                                }
                            }
                        }
                        if (showBar) {
                            Spacer(Modifier.height(12.dp))
                            MiniPlayer(engine, actions) { tab = Tab.PLAYING }
                            Spacer(Modifier.height(16.dp))
                        } else Spacer(Modifier.height(16.dp))
                    }
                }
            }
            (if (right) parts.reversed() else parts).forEach { it() }
        }
    }
    }
}

/** Page background: radial glows from the design (g1 bottom-right, g2 top-left). */
@Composable
private fun Modifier.pageGlow(): Modifier {
    val c = LocalDabColors.current
    return this.then(Modifier.background(Brush.radialGradient(listOf(c.glow2, Color.Transparent), center = Offset(250f, -70f), radius = 900f)))
        .then(Modifier.background(Brush.radialGradient(listOf(c.glow1, Color.Transparent), center = Offset(1150f, 830f), radius = 700f)))
}

@Composable
private fun Clock(size: Int) {
    val format = rememberTimeFormatter()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }
    Text(format(now), color = LocalDabColors.current.text, fontSize = size.sp, fontWeight = FontWeight.Medium)
}

/**
 * Navigation rail, the same in both layouts: icons only, no labels (owner, 2026-09-28; the design handoff had a labelled,
 * filled rail for layout 1b). Fading divider on the content side.
 */
@Composable
private fun Rail(tab: Tab, right: Boolean, onSelect: (Tab) -> Unit) {
    val c = LocalDabColors.current
    val night = LocalNight.current
    Box(Modifier.width(120.dp).fillMaxHeight()) {
        Column(Modifier.fillMaxSize().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Clock(24)
            Spacer(Modifier.height(12.dp))
            for (t in Tab.entries) {
                val active = t == tab
                val tint = when { active -> Accent.onAccent; night -> t.color.copy(alpha = 0.7f); else -> c.neutral500 }
                Box(
                    Modifier.size(92.dp)
                        .then(if (active) Modifier.shadow(18.dp, RoundedCornerShape(14.dp), ambientColor = t.color, spotColor = t.color) else Modifier)
                        .clip(RoundedCornerShape(14.dp)).background(if (active) t.color else Color.Transparent)
                        .pressable({ onSelect(t) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = tint, modifier = Modifier.size(42.dp))
                }
            }
        }
        Canvas(Modifier.fillMaxHeight().width(1.dp).align(if (right) Alignment.CenterStart else Alignment.CenterEnd)) {
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 48f / size.height to c.neutral800,
                1f - 48f / size.height to c.neutral800, 1f to Color.Transparent))
        }
    }
}

/**
 * What the player says while the signal is gone. During a following round it doesn't name channels: failed tunes
 * take ~0.2 s each, so names would flash by (verified 2026-09-27).
 */
fun noSignalText(e: EngineState) = if (e.following != null) "No signal · searching other multiplexes…" else "No signal · waiting for reception"

/** Subtitle colour: red for real problems, amber while the signal is gone (it comes back by itself). */
@Composable
fun statusColor(e: EngineState, normal: Color) = when {
    e.problem != null -> Accent.liveRed
    e.playing && e.reception == Reception.LOST -> Accent.amber
    e.current != null && !e.dongle && !e.playing -> Accent.amber       // last station shown, dongle not (yet) there
    else -> normal
}

/** Title/artist lines for the player: always "Artist – Title" for songs; raw DLS otherwise. */
data class NowLines(val title: String, val subtitle: String?)

fun nowLines(e: EngineState): NowLines {
    val st = e.current
    val np = e.nowPlaying
    return when {
        st == null -> NowLines("No station", "Pick a station to start")
        !e.dongle && !e.playing -> NowLines(st.label, "DAB dongle not connected")
        e.problem != null -> NowLines(st.label, e.problem)
        e.tuning -> NowLines(st.label, "Tuning…")
        e.playing && e.reception == Reception.LOST -> NowLines(st.label, noSignalText(e))
        np == null -> NowLines(st.label, null)
        np.isSong -> NowLines(st.label, np.display)
        else -> NowLines(st.label, np.raw)
    }
}

/** 1a mini player bar: 104dp, logo 80, station 24/500, "Artist – Title" 18, controls 80. */
@Composable
private fun MiniPlayer(e: EngineState, a: RadioActions, open: () -> Unit) {
    val c = LocalDabColors.current
    val lines = nowLines(e)
    Row(Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).pressable(open).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Artwork(e.slide, e.current?.label ?: "", e.current?.key, 80.dp, 80.dp, radius = 10.dp, preferLogo = true)
        Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lines.title, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (e.current != null && e.playing) SignalBars(e.signalBars, 14.dp, Modifier.padding(start = 12.dp))
            }
            lines.subtitle?.let {
                Text(it, color = statusColor(e, c.neutral400), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SquareButton(PhosphorIcons.Fill.SkipBack, "Previous", 80.dp, { a.skip(-1) }, background = Color.Transparent)
        Spacer(Modifier.width(12.dp))
        PlayButton(e.playing, 80.dp, a.togglePlay, busy = e.tuning)
        Spacer(Modifier.width(12.dp))
        SquareButton(PhosphorIcons.Fill.SkipForward, "Next", 80.dp, { a.skip(+1) }, background = Color.Transparent)
    }
}

/** 1b docked player panel: 400dp, panel gradient, 4:3 artwork, station, "Artist – Title", controls (play 112). */
@Composable
private fun PlayerPanel(e: EngineState, db: Snapshot, a: RadioActions, open: () -> Unit) {
    val c = LocalDabColors.current
    val lines = nowLines(e)
    Column(
        Modifier.width(400.dp).fillMaxHeight()
            .background(Brush.linearGradient(listOf(c.panelFrom, c.panelMid, c.panelTo), start = Offset(0f, 0f), end = Offset(300f, 900f)))
            .padding(28.dp),
    ) {
        Box(Modifier.pressable(open)) { Artwork(e.slide, e.current?.label ?: "", e.current?.key, 344.dp, 258.dp) }
        Spacer(Modifier.height(24.dp))
        if (e.current != null && e.playing) SignalBars(e.signalBars, 16.dp)
        Text(lines.title, color = c.text, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
        lines.subtitle?.let {
            Text(it, color = statusColor(e, c.neutral300), fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SquareButton(PhosphorIcons.Fill.SkipBack, "Previous", 88.dp, { a.skip(-1) }, background = c.bg.copy(alpha = 0.5f), radius = 16.dp)
            PlayButton(e.playing, 112.dp, a.togglePlay, busy = e.tuning)
            SquareButton(PhosphorIcons.Fill.SkipForward, "Next", 88.dp, { a.skip(+1) }, background = c.bg.copy(alpha = 0.5f), radius = 16.dp)
        }
    }
}

/**
 * Artwork frame (4:3 for DAB slides, 320×240 native): the current slide, else the station logo, cross-faded over 300ms.
 * [preferLogo] is for small places (mini bar) where a 320×240 news card would be unreadable.
 */
@Composable
fun Artwork(slide: ByteArray?, label: String, key: ServiceKey?, width: Dp, height: Dp, radius: Dp = 18.dp, preferLogo: Boolean = false,
            modifier: Modifier = Modifier) {
    val c = LocalDabColors.current
    val bmp = rememberSlide(slide)                  // decoded off the UI thread: a new slide never costs a frame
    Box(modifier.size(width, height).clip(RoundedCornerShape(radius)).background(c.surface), contentAlignment = Alignment.Center) {
        Crossfade(if (preferLogo) null else bmp, animationSpec = tween(300), label = "art") { b ->
            if (b != null) Image(b, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (label.isNotEmpty()) StationLogo(label, key, minOf(width, height) * if (preferLogo) 1f else 0.62f,
                    radius = if (preferLogo) radius else 22.dp)
            }
        }
    }
}

@Composable
fun ScreenTitle(text: String, compact: Boolean, trailing: @Composable RowScope.() -> Unit = {}, extra: String? = null) {
    val c = LocalDabColors.current
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = c.text, fontSize = if (compact) 30.sp else 34.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp)
        extra?.let { Text(it, color = c.neutral500, fontSize = 20.sp, modifier = Modifier.padding(start = 14.dp, top = 6.dp)) }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun BoxScope.EmptyHint(text: String) {
    Text(text, color = LocalDabColors.current.neutral400, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
}
