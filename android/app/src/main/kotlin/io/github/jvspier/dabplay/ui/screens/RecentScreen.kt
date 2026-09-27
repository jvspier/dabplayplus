package io.github.jvspier.dabplay.ui.screens

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.ArrowCounterClockwise
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.Trash
import io.github.jvspier.dabplay.data.Recent
import io.github.jvspier.dabplay.data.Snapshot
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
import kotlinx.coroutines.delay
import java.util.Calendar

@Composable
fun RecentScreen(e: EngineState, db: Snapshot, a: RadioActions, compactTitle: Boolean) {
    val c = LocalDabColors.current
    var undo by remember { mutableStateOf<List<Recent>?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(6_000); undo = null } }
    val rows = remember(db.recents, db.ensembles) { db.recents.mapNotNull { r -> db.station(r.key)?.let { r to it } } }
    val favSet = remember(db.favourites) { db.favourites.toSet() }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Recently played", compactTitle, trailing = {
            if (rows.isNotEmpty()) Row(Modifier.height(64.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
                .pressable({ undo = a.clearRecents() }).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PhosphorIcons.Fill.Trash, null, tint = c.neutral300, modifier = Modifier.size(26.dp))
                Text("Clear", color = c.neutral200, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp))
            }
        })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty() && undo == null) EmptyHint("Stations you listen to show up here")
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(rows, key = { it.first.key.toString() }) { (r, st) ->
                    val current = st.key == e.current?.key
                    Row(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
                        .pressable({ a.play(st.key) }).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        StationLogo(st.label, st.key, 72.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                            Text(st.label, color = if (current) c.liveSub else c.text, fontSize = 25.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (current && e.playing) "Playing now" else whenText(r.time), color = c.neutral400, fontSize = 18.sp,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                        Box(Modifier.size(72.dp).pressable({ a.toggleFavourite(st.key) }), contentAlignment = Alignment.Center) {
                            HeartIcon(st.key in favSet, 30.dp)
                        }
                        Box(Modifier.padding(start = 8.dp).size(72.dp).clip(CircleShape).background(Accent.orange.copy(alpha = 0.16f))
                            .pressable({ if (current) a.togglePlay() else a.play(st.key) }), contentAlignment = Alignment.Center) {
                            if (current && e.playing) Waveform(true, 30.dp)
                            else Icon(PhosphorIcons.Fill.Play, "Play", tint = Accent.orange, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(undo != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 2 },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(220)) { it / 2 }) {
                Row(Modifier.shadow(16.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.neutral800)
                    .padding(start = 26.dp, end = 8.dp).height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent cleared", color = c.text, fontSize = 20.sp)
                    Row(Modifier.padding(start = 24.dp).height(56.dp).clip(RoundedCornerShape(12.dp))
                        .pressable({ undo?.let(a.restoreRecents); undo = null }).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(PhosphorIcons.Fill.ArrowCounterClockwise, null, tint = Accent.orange, modifier = Modifier.size(24.dp))
                        Text("Undo", color = Accent.orange, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** "Today, 08:14", "Yesterday, 17:40", "Monday, 12:03", else a short date. Follows the system 12/24h setting. */
@Composable
private fun whenText(t: Long): String {
    val ctx = LocalContext.current
    return remember(t) {
        val time = DateFormat.getTimeFormat(ctx).format(t)
        val then = Calendar.getInstance().apply { timeInMillis = t }
        // midnight today, stepped back a day at a time (DST-safe, unlike dividing by 24h)
        val day = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        var d = 0
        while (t < day.timeInMillis && d < 7) { day.add(Calendar.DAY_OF_YEAR, -1); d++ }
        if (t < day.timeInMillis) d = 7
        when (d) {
            0 -> "Today, $time"
            1 -> "Yesterday, $time"
            in 2..6 -> "${DateFormat.format("EEEE", then)}, $time"
            else -> "${DateFormat.getMediumDateFormat(ctx).format(t)}, $time"
        }
    }
}
