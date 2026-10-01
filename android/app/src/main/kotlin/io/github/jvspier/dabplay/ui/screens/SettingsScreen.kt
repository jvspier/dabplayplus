package io.github.jvspier.dabplay.ui.screens

import androidx.compose.runtime.remember
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.activity.compose.BackHandler
import io.github.jvspier.dabplay.data.StationStore
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.ArrowLeft
import com.adamglin.phosphoricons.fill.ArrowsClockwise
import com.adamglin.phosphoricons.fill.CaretDown
import com.adamglin.phosphoricons.fill.Car
import com.adamglin.phosphoricons.fill.CaretRight
import com.adamglin.phosphoricons.fill.CaretUp
import com.adamglin.phosphoricons.fill.Clock
import com.adamglin.phosphoricons.fill.CircleHalf
import com.adamglin.phosphoricons.fill.DownloadSimple
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Layout
import com.adamglin.phosphoricons.fill.Moon
import com.adamglin.phosphoricons.fill.Rows
import com.adamglin.phosphoricons.fill.Sidebar
import com.adamglin.phosphoricons.fill.SignIn
import com.adamglin.phosphoricons.fill.SteeringWheel
import com.adamglin.phosphoricons.fill.Sun
import com.adamglin.phosphoricons.fill.TrafficCone
import com.adamglin.phosphoricons.fill.X
import io.github.jvspier.dabplay.data.ClockFormat
import io.github.jvspier.dabplay.data.DisplayMode
import io.github.jvspier.dabplay.data.DriverSide
import io.github.jvspier.dabplay.data.LayoutVariant
import io.github.jvspier.dabplay.data.Settings
import io.github.jvspier.dabplay.data.Snapshot
import io.github.jvspier.dabplay.logos.LogoStatus
import io.github.jvspier.dabplay.ui.EmptyHint
import io.github.jvspier.dabplay.ui.RadioActions
import io.github.jvspier.dabplay.ui.ScreenTitle
import io.github.jvspier.dabplay.ui.components.DabSwitch
import io.github.jvspier.dabplay.ui.components.IconTile
import io.github.jvspier.dabplay.ui.components.LocalLogos
import io.github.jvspier.dabplay.ui.components.Segmented
import io.github.jvspier.dabplay.ui.components.SquareButton
import io.github.jvspier.dabplay.ui.components.StationLogo
import io.github.jvspier.dabplay.ui.components.pressable
import io.github.jvspier.dabplay.ui.theme.Accent
import io.github.jvspier.dabplay.ui.theme.LocalDabColors

private val ON_AMBER = Color(0xFF2A1A00)

@Composable
fun SettingsScreen(s: Settings, db: Snapshot, a: RadioActions, compactTitle: Boolean, logoStatus: LogoStatus = LogoStatus()) {
    var editing by rememberSaveable { mutableStateOf(false) }
    AnimatedContent(editing, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "settings") { edit ->
        if (edit) EditFavourites(db, a, compactTitle) { editing = false }
        else SettingsList(s, db, a, compactTitle, logoStatus) { editing = true }
    }
}

@Composable
private fun SettingsList(s: Settings, db: Snapshot, a: RadioActions, compact: Boolean, logoStatus: LogoStatus, onEditFavourites: () -> Unit) {
    val tile = if (compact) 44.dp else 64.dp
    val segW = if (compact) 104.dp else 120.dp
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Settings", compact)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingRow(PhosphorIcons.Fill.CircleHalf, Accent.amber, tile, "Display", "Day, night, or follow the system") {
                Segmented(listOf(DisplayMode.DAY to "Day", DisplayMode.AUTO to "Auto", DisplayMode.NIGHT to "Night"), s.display, Accent.amber, ON_AMBER,
                    { v -> a.updateSettings { it.copy(display = v) } }, optionWidth = segW,
                    icons = mapOf(DisplayMode.DAY to PhosphorIcons.Fill.Sun, DisplayMode.AUTO to PhosphorIcons.Fill.CircleHalf, DisplayMode.NIGHT to PhosphorIcons.Fill.Moon))
            }
            SettingRow(PhosphorIcons.Fill.Layout, Accent.orange, tile, "Layout", "Player bar at the bottom, or a panel at the side") {
                Segmented(listOf(LayoutVariant.BAR to "Bar", LayoutVariant.PANEL to "Panel"), s.layout, Accent.amber, ON_AMBER,
                    { v -> a.updateSettings { it.copy(layout = v) } }, optionWidth = segW,
                    icons = mapOf(LayoutVariant.BAR to PhosphorIcons.Fill.Rows, LayoutVariant.PANEL to PhosphorIcons.Fill.Sidebar))
            }
            SettingRow(PhosphorIcons.Fill.SteeringWheel, Accent.lavender, tile, "Driver side", "Puts the controls nearest to you") {
                Segmented(listOf(DriverSide.LEFT to "Left", DriverSide.RIGHT to "Right"), s.driverSide, Accent.amber, ON_AMBER,
                    { v -> a.updateSettings { it.copy(driverSide = v) } }, optionWidth = segW)
            }
            SettingRow(PhosphorIcons.Fill.Clock, Accent.blue, tile, "Clock", "System follows the Android setting") {
                Segmented(listOf(ClockFormat.SYSTEM to "System", ClockFormat.H12 to "12h", ClockFormat.H24 to "24h"), s.clock, Accent.amber, ON_AMBER,
                    { v -> a.updateSettings { it.copy(clock = v) } }, optionWidth = segW)
            }
            // Android 10+ only lets an app open itself at start-up with "Display over other apps" allowed
            val ctx = LocalContext.current
            var canOpenItself by remember { mutableStateOf(canDrawOverlays(ctx)) }
            LifecycleResumeEffect(Unit) { canOpenItself = canDrawOverlays(ctx); onPauseOrDispose { } }
            val needsOverlay = s.startWithCar && !canOpenItself
            SettingRow(PhosphorIcons.Fill.Car, Accent.green, tile, "Start with the car",
                when {
                    needsOverlay -> "On · tap to allow \"Display over other apps\": Android needs it for this"
                    s.startWithCar -> "On · opens DABplay+ when the car starts"
                    else -> "Off · open DABplay+ yourself, e.g. when you use CarPlay"
                },
                onClick = if (needsOverlay) ({ openOverlaySettings(ctx) }) else null) {
                DabSwitch(s.startWithCar) { v -> a.updateSettings { it.copy(startWithCar = v) } }
            }
            SettingRow(PhosphorIcons.Fill.SignIn, Accent.blue, tile, "Resume last station", "Start playing when DABplay+ opens") {
                DabSwitch(s.resumeOnStart) { v -> a.updateSettings { it.copy(resumeOnStart = v) } }
            }
            SettingRow(PhosphorIcons.Fill.DownloadSimple, Accent.green, tile, "Download station logos", logoLine(s.downloadLogos, logoStatus, db)) {
                DabSwitch(s.downloadLogos) { v -> a.updateSettings { it.copy(downloadLogos = v) } }
            }
            SettingRow(PhosphorIcons.Fill.ArrowsClockwise, Accent.orange, tile, "Follow station", "Switch to a stronger signal of the same station") {
                DabSwitch(s.followStation) { v -> a.updateSettings { it.copy(followStation = v) } }
            }
            SettingRow(PhosphorIcons.Fill.TrafficCone, Accent.amber, tile, "Traffic announcements",
                if (s.trafficAnnouncements) "On · switches to traffic news on the same multiplex" else "Off · alarm announcements still come through") {
                DabSwitch(s.trafficAnnouncements) { v -> a.updateSettings { it.copy(trafficAnnouncements = v) } }
            }
            SettingRow(PhosphorIcons.Fill.Heart, Accent.pink, tile, "Edit favorites", "${db.favourites.size} of ${StationStore.MAX_FAVOURITES} · or long-press a tile on Home and drag it", onClick = onEditFavourites) {
                Icon(PhosphorIcons.Fill.CaretRight, null, tint = LocalDabColors.current.neutral400, modifier = Modifier.size(30.dp))
            }
        }
    }
}

/** Tells what the logo download is doing: it's automatic, so the row shows its state instead of a button. */
@Composable
private fun logoLine(enabled: Boolean, st: LogoStatus, db: Snapshot): String {
    val logos = LocalLogos.current
    val total = db.stations.size
    val have = db.stations.count { it.key in logos }
    return when {
        !enabled -> "Off · stations show their initials"
        total == 0 -> "Automatic when online · scan for stations first"
        st.running && st.online -> "Downloading… $have of $total"
        !st.online -> "Automatic when online · no internet now · $have of $total have a logo"
        else -> "Automatic when online · $have of $total have a logo"
    }
}

@Composable
private fun SettingRow(icon: ImageVector, color: Color, tile: Dp, title: String, subtitle: String, onClick: (() -> Unit)? = null,
                       control: @Composable () -> Unit) {
    val c = LocalDabColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
        .then(if (onClick != null) Modifier.pressable(onClick) else Modifier).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, color, tile)
        Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
            Text(title, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = c.neutral400, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        control()
    }
}

/**
 * Reorder with up/down, remove with ×. Big buttons instead of drag handles: easier with one hand in a moving car.
 * Lists every favourite, also those whose station wasn't found in the last scans (Home can't show them, but they
 * still take a place of the 24; here they can be removed).
 */
@Composable
private fun EditFavourites(db: Snapshot, a: RadioActions, compact: Boolean, onBack: () -> Unit) {
    val c = LocalDabColors.current
    val favs = db.favourites
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquareButton(PhosphorIcons.Fill.ArrowLeft, "Back", 64.dp, onBack)
            Spacer(Modifier.width(18.dp))
            Box(Modifier.weight(1f)) { ScreenTitle("Edit favorites", compact, extra = "${favs.size} of ${StationStore.MAX_FAVOURITES}") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (favs.isEmpty()) EmptyHint("No favorites yet: tap a heart on the Stations tab")
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                itemsIndexed(favs, key = { _, k -> k.toString() }) { i, key ->
                    val st = db.station(key)
                    Row(Modifier.animateItem().fillMaxWidth().height(88.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = c.neutral500, fontSize = 20.sp, modifier = Modifier.width(36.dp).padding(start = 6.dp))
                        StationLogo(st?.label ?: "?", key, 60.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                            Text(st?.label ?: "Unknown station", color = if (st != null) c.text else c.neutral400, fontSize = 24.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (st == null) Text("Not found in the last scans", color = c.neutral500, fontSize = 17.sp, maxLines = 1)
                        }
                        val dim = c.neutral700
                        SquareButton(PhosphorIcons.Fill.CaretUp, "Move up", 64.dp, { if (i > 0) a.moveFavourite(key, favs[i - 1]) },
                            background = c.bg, tint = if (i > 0) c.neutral200 else dim)
                        Spacer(Modifier.width(10.dp))
                        SquareButton(PhosphorIcons.Fill.CaretDown, "Move down", 64.dp, { if (i < favs.lastIndex) a.moveFavourite(key, favs[i + 1]) },
                            background = c.bg, tint = if (i < favs.lastIndex) c.neutral200 else dim)
                        Spacer(Modifier.width(10.dp))
                        SquareButton(PhosphorIcons.Fill.X, "Remove", 64.dp, { a.toggleFavourite(key) },
                            background = Accent.pink.copy(alpha = 0.14f), tint = c.pinkInk)
                    }
                }
            }
        }
    }
}

private fun canDrawOverlays(ctx: Context) = Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(ctx)

/** The system screen for "Display over other apps", for this app (falls back to the full list). */
private fun openOverlaySettings(ctx: Context) {
    val pkg = Uri.parse("package:${ctx.packageName}")
    runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .recoverCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
