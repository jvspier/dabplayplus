package io.github.jvspier.dabplay.ui

import androidx.activity.compose.BackHandler
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.adamglin.phosphoricons.fill.Siren
import com.adamglin.phosphoricons.fill.TrafficCone
import io.github.jvspier.dabplay.core.Announcement
import io.github.jvspier.dabplay.engine.AnnouncementInfo
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.adamglin.phosphoricons.fill.Play
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
    PLAYING("Playing", Accent.orange, PhosphorIcons.Fill.Play),        // a play triangle: a waveform wasn't read as "now playing" (owner, 2026-10-18)
    RECENT("Recent", Accent.green, PhosphorIcons.Fill.ClockCounterClockwise),
    SETTINGS("Settings", Accent.lavender, PhosphorIcons.Fill.GearSix),
}

/** Everything the UI can ask the radio to do. */
class RadioActions(
    val play: (ServiceKey) -> Unit,
    val togglePlay: () -> Unit,
    val skip: (Int) -> Unit,
    val toggleFavourite: (ServiceKey) -> Unit,
    val moveFavourite: (key: ServiceKey, to: ServiceKey) -> Unit,
    val scan: () -> Unit,
    val clearRecents: () -> List<Recent>,
    val restoreRecents: (List<Recent>) -> Unit,
    val updateSettings: ((Settings) -> Settings) -> Unit,
    val skipAnnouncement: () -> Unit = {},
    val retryLogos: () -> Unit = {},
    val refreshLogos: () -> Unit = {},
    val shareDiagnostics: () -> Unit = {},
    /** A name for the file, and saving it where the user picked (Storage Access Framework). */
    val diagnosticsFileName: () -> String = { "dabplay-diagnostics.txt" },
    val saveDiagnostics: (android.net.Uri) -> Unit = {},
)

@Composable
fun AppShell(engine: EngineState, db: Snapshot, settings: Settings, actions: RadioActions, logoStatus: LogoStatus = LogoStatus()) {
    val c = LocalDabColors.current
    var tab by rememberSaveable { mutableStateOf(if (db.favourites.isEmpty() && db.stations.isNotEmpty()) Tab.STATIONS else Tab.HOME) }
    val saved = rememberSaveableStateHolder()
    // Back never leaves the radio by accident (the system bar slides in over the mini player): another tab → Home,
    // Home → nothing; leaving is the head unit's home button (owner, 2026-10-01). Edit favourites has its own Back.
    BackHandler { if (tab != Tab.HOME) tab = Tab.HOME }
    val right = settings.driverSide == DriverSide.RIGHT

    // the Now-playing glow fades in over the whole screen (not just the content column), 300ms
    val npGlow = animateFloatAsState(if (tab == Tab.PLAYING) 1f else 0f, tween(300), label = "npGlow")
    AdaptiveSizing {
    CompositionLocalProvider(LocalClockFormat provides settings.clock, LocalNow provides rememberNow()) {
    Box(Modifier.fillMaxSize()) {
        PageBackground { npGlow.value }
        // everything fits inside the head unit's bars that are on screen (Full screen off, or a unit that shows them
        // anyway; hidden bars take no space); the background still fills it all
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        // the side panel gives way on narrow screens (a 1024×600 unit), and where even then the screens next to it
        // would be too narrow to read (a 1280×720 unit at a higher density is 853 dp wide), the bar layout is used
        val panelW = minOf(PANEL_W, (maxWidth - RAIL_W) * 0.42f).coerceAtLeast(PANEL_MIN_W)
        val panelFits = maxWidth - RAIL_W - panelW >= PANEL_CONTENT_MIN
        val panelLayout = settings.layout == LayoutVariant.PANEL && panelFits
        val showPanel = panelLayout && tab != Tab.PLAYING
        val showBar = !panelLayout && tab != Tab.PLAYING
        Row(Modifier.fillMaxSize()) {
            val parts = buildList<@Composable RowScope.() -> Unit> {
                add { Rail(tab, right) { tab = it } }
                if (showPanel) add { PlayerPanel(engine, db, actions, panelW) { tab = Tab.PLAYING } }
                add {
                    Column(Modifier.weight(1f).fillMaxHeight().padding(start = if (!right && !showPanel) 8.dp else 28.dp, end = if (right && !showPanel) 8.dp else 28.dp)) {
                        // short crossfade between tabs; each tab keeps its scroll position while you're elsewhere
                        Crossfade(tab, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(180), label = "tab") { t ->
                            saved.SaveableStateProvider(t.name) {
                                when (t) {
                                    Tab.HOME -> HomeScreen(engine, db, actions, compactTitle = panelLayout, onBrowse = { tab = Tab.STATIONS })
                                    Tab.STATIONS -> StationsScreen(engine, db, actions, compactTitle = panelLayout)
                                    Tab.PLAYING -> NowPlayingScreen(engine, db, actions, chipOnLeft = right, taOn = settings.trafficAnnouncements)
                                    Tab.RECENT -> RecentScreen(engine, db, actions, compactTitle = panelLayout)
                                    Tab.SETTINGS -> SettingsScreen(settings, db, actions, compactTitle = panelLayout, logoStatus = logoStatus, panelFits = panelFits)
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
        AnnouncementBanner(engine.announcement.takeIf { engine.dongle }, actions.skipAnnouncement, Modifier.align(Alignment.TopCenter))
    }
    }
    }
    }
}

private val RAIL_W = 120.dp
private val PANEL_W = 400.dp
private val PANEL_MIN_W = 300.dp
/** The least the screens next to the side panel get (rail and panel taken off); less and the bar layout is used. */
private val PANEL_CONTENT_MIN = 480.dp

/**
 * A traffic (or alarm) announcement is playing instead of your station: say so, clearly, over any screen, with a big
 * Skip for traffic (alarms can't be skipped). Back to your station happens by itself when it ends.
 */
@Composable
private fun AnnouncementBanner(a: AnnouncementInfo?, onSkip: () -> Unit, modifier: Modifier) {
    var last by remember { mutableStateOf(a) }
    if (a != null) last = a
    AnimatedVisibility(a != null, modifier.padding(top = 16.dp),
        enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { -it }, exit = fadeOut(tween(200)) + slideOutVertically(tween(260)) { -it }) {
        val info = last ?: return@AnimatedVisibility
        val alarm = info.flags and Announcement.ALARM != 0
        val bg = if (alarm) Accent.liveRed else Accent.amber
        val ink = if (alarm) Color.White else Color(0xFF2A1A00)
        Row(Modifier.shadow(24.dp, RoundedCornerShape(18.dp), ambientColor = bg, spotColor = bg).clip(RoundedCornerShape(18.dp))
            .background(bg).padding(start = 22.dp, end = if (alarm) 26.dp else 10.dp).height(84.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (alarm) PhosphorIcons.Fill.Siren else PhosphorIcons.Fill.TrafficCone, null, tint = ink, modifier = Modifier.size(36.dp))
            Column(Modifier.padding(start = 16.dp, end = 24.dp)) {
                Text(if (alarm) "Alarm announcement" else "Traffic announcement", color = ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(info.station, color = ink.copy(alpha = 0.8f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!alarm) Box(Modifier.height(64.dp).clip(RoundedCornerShape(14.dp)).background(ink.copy(alpha = 0.12f))
                .pressable(onSkip).padding(horizontal = 26.dp), contentAlignment = Alignment.Center) {
                Text("Skip", color = ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The page: background colour and the design's radial glows (g1 bottom-right, g2 top-left, the Now-playing glow), as
 * one cached layer. It's redrawn only when the theme changes or the Now-playing glow fades, and otherwise goes on
 * screen as a single texture. Drawn directly, it painted every pixel four times per frame, which a head unit's GPU
 * feels at 1280×720 (measured with the overdraw overlay, 2026-09-29).
 */
@Composable
private fun PageBackground(npGlow: () -> Float) {
    val c = LocalDabColors.current
    Box(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawBehind {
        drawRect(c.bg)
        // positions from the 1280×720 design, scaled to the actual screen
        val sx = size.width / 1280f; val sy = size.height / 720f
        drawRect(Brush.radialGradient(listOf(c.glow2, Color.Transparent), center = Offset(250f * sx, -70f * sy), radius = 900f * sx))
        drawRect(Brush.radialGradient(listOf(c.glow1, Color.Transparent), center = Offset(1150f * sx, 830f * sy), radius = 700f * sx))
        val np = npGlow()                         // read here: the fade redraws this layer only, nothing recomposes
        if (np > 0f) drawRect(Brush.radialGradient(listOf(c.npGlow, Color.Transparent), center = Offset(size.width * 0.45f, size.height * 0.5f),
            radius = size.width * 0.6f), alpha = np)
    })
}

@Composable
private fun Clock(size: Int) {
    val format = rememberTimeFormatter()
    Text(format(LocalNow.current), color = LocalDabColors.current.text, fontSize = size.sp, fontWeight = FontWeight.Medium)
}

/**
 * Navigation rail, the same in both layouts: icons only, no labels (owner, 2026-09-28; the design handoff had a labelled,
 * filled rail for layout 1b). Fading divider on the content side.
 */
@Composable
private fun Rail(tab: Tab, right: Boolean, onSelect: (Tab) -> Unit) {
    val c = LocalDabColors.current
    val night = LocalNight.current
    BoxWithConstraints(Modifier.width(RAIL_W).fillMaxHeight()) {
        // every tab always fits, no scrolling: 92 dp each where there's room (the Dudu3), smaller on a shorter screen
        // (a 1024×600 unit: 64). Where they'd get smaller than RAIL_ITEM_MIN, the clock makes room for them.
        val n = Tab.entries.size
        val withClock = (maxHeight - RAIL_FIXED) / n
        val showClock = withClock >= RAIL_ITEM_MIN
        val item = (if (showClock) withClock else (maxHeight - RAIL_FIXED_NO_CLOCK) / n).coerceIn(RAIL_ITEM_FLOOR, RAIL_ITEM)
        Column(Modifier.fillMaxSize().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (showClock) {
                Clock(24)
                Spacer(Modifier.height(12.dp))
            }
            for (t in Tab.entries) {
                val active = t == tab
                // tab colours in both themes (owner, 2026-09-28); by day a bit darker, so amber and green read on white
                val tint = when { active -> Accent.onAccent; night -> t.color.copy(alpha = 0.7f); else -> lerp(t.color, Color.Black, 0.22f) }
                Box(
                    Modifier.size(item)
                        .then(if (active) Modifier.shadow(18.dp, RoundedCornerShape(14.dp), ambientColor = t.color, spotColor = t.color) else Modifier)
                        .clip(RoundedCornerShape(14.dp)).background(if (active) t.color else Color.Transparent)
                        .pressable({ onSelect(t) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = tint, modifier = Modifier.size(item * 42 / 92))
                }
            }
        }
        Canvas(Modifier.fillMaxHeight().width(1.dp).align(if (right) Alignment.CenterStart else Alignment.CenterEnd)) {
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 48f / size.height to c.neutral800,
                1f - 48f / size.height to c.neutral800, 1f to Color.Transparent))
        }
    }
}

private val RAIL_ITEM = 92.dp
private val RAIL_ITEM_MIN = 56.dp
/** Only so a tab never gets absurd in a tiny window (split screen); the rail never scrolls. */
private val RAIL_ITEM_FLOOR = 24.dp
/** The rail without its tabs: top padding, clock, spacer, the gaps between them all, a bottom margin. */
private val RAIL_FIXED = 20.dp + 32.dp + 12.dp + 14.dp * (Tab.entries.size + 1) + 12.dp
private val RAIL_FIXED_NO_CLOCK = 20.dp + 14.dp * (Tab.entries.size - 1) + 12.dp

/**
 * What the player says while the signal is gone. During a following round it doesn't name channels: failed tunes
 * take ~0.2 s each, so names would flash by (verified 2026-09-27).
 */
fun noSignalText(e: EngineState) = if (e.following != null) "No signal · searching other multiplexes…" else "No signal · waiting for reception"

/** Subtitle colour: red for real problems, amber while the signal is gone (it comes back by itself). */
@Composable
fun statusColor(e: EngineState, normal: Color) = when {
    e.problem != null -> LocalDabColors.current.error
    e.playing && e.reception == Reception.LOST -> LocalDabColors.current.warn
    e.current != null && !e.dongle && !e.playing -> LocalDabColors.current.warn   // last station shown, dongle not (yet) there
    else -> normal
}

/** Title/artist lines for the player: always "Artist – Title" for songs; raw DLS otherwise. */
data class NowLines(val title: String, val subtitle: String?)

fun nowLines(e: EngineState): NowLines {
    val st = e.current
    val np = e.nowPlaying
    return when {
        st == null -> NowLines("No station", "Pick a station to start")
        e.unsupportedDongle -> NowLines(st.label, "USB device not supported")
        !e.dongle && e.notResponding -> NowLines(st.label, NOT_RESPONDING)
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

/**
 * 1b docked player panel: 400dp, panel gradient, 4:3 artwork, station, "Artist – Title", controls (play 112). Narrower
 * on a narrow screen, and the artwork smaller on a short one, so the controls always fit below it.
 */
@Composable
private fun PlayerPanel(e: EngineState, db: Snapshot, a: RadioActions, width: Dp, open: () -> Unit) {
    val c = LocalDabColors.current
    val lines = nowLines(e)
    BoxWithConstraints(
        Modifier.width(width).fillMaxHeight()
            .background(Brush.linearGradient(listOf(c.panelFrom, c.panelMid, c.panelTo), start = Offset(0f, 0f), end = Offset(300f, 900f)))
            .padding(28.dp),
    ) {
    // the design's 344×258, or what's left above the text and controls (~300 dp of the design's 580)
    val artW = minOf(maxWidth, 344.dp, (maxHeight - PANEL_BELOW_ART) * 4 / 3).coerceAtLeast(96.dp)
    val side = minOf(88.dp, maxWidth * 0.3f)
    val play = minOf(112.dp, maxWidth * 0.38f)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.pressable(open)) { Artwork(e.slide, e.current?.label ?: "", e.current?.key, artW, artW * 3 / 4) }
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
            SquareButton(PhosphorIcons.Fill.SkipBack, "Previous", side, { a.skip(-1) }, background = c.bg.copy(alpha = 0.5f), radius = 16.dp)
            PlayButton(e.playing, play, a.togglePlay, busy = e.tuning)
            SquareButton(PhosphorIcons.Fill.SkipForward, "Next", side, { a.skip(+1) }, background = c.bg.copy(alpha = 0.5f), radius = 16.dp)
        }
    }
    }
}

/** Below the panel's artwork: the gap, signal, station (30 sp), two lines of "Artist – Title" (22 sp), the controls. */
private val PANEL_BELOW_ART = 24.dp + 16.dp + 8.dp + 40.dp + 6.dp + 60.dp + 24.dp + 112.dp

/**
 * Artwork frame (4:3 for DAB slides, 320×240 native): the current slide, else the station logo, cross-faded over 300ms.
 * [preferLogo] is for small places (mini bar) where a 320×240 news card would be unreadable.
 */
@Composable
fun Artwork(slide: ByteArray?, label: String, key: ServiceKey?, width: Dp, height: Dp, radius: Dp = 18.dp, preferLogo: Boolean = false,
            modifier: Modifier = Modifier) {
    val c = LocalDabColors.current
    val bmp = if (preferLogo) null else rememberSlide(slide, key)    // decoded off the UI thread: a new slide never costs a frame
    Box(modifier.size(width, height).clip(RoundedCornerShape(radius)).background(c.surface), contentAlignment = Alignment.Center) {
        Crossfade(bmp, animationSpec = tween(300), label = "art") { b ->
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

/** A device with the DAB dongle's USB id that isn't one DABplay+ can use (the log has its details: issue #1). */
const val UNSUPPORTED_DONGLE = "This USB device has a DAB dongle's ID, but DABplay+ can't use it"
/** Plugged in, but it doesn't answer (yet): the engine keeps reconnecting. */
const val NOT_RESPONDING = "DAB dongle not responding, retrying…"

@Composable
fun BoxScope.EmptyHint(text: String) {
    Text(text, color = LocalDabColors.current.neutral400, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
}
