package io.github.jvspier.dabplay.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jvspier.dabplay.data.Snapshot
import io.github.jvspier.dabplay.data.Station
import io.github.jvspier.dabplay.engine.EngineState
import io.github.jvspier.dabplay.ui.theme.Accent
import io.github.jvspier.dabplay.ui.theme.LocalDabColors

/** Temporary engine-test screen (scan, all stations, favourites via long-press, play/pause). Not the real UI. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadioTestScreen(s: EngineState, db: Snapshot, onPlay: (Station) -> Unit, onFavourite: (Station) -> Unit,
                    onScan: () -> Unit, onPlayPause: () -> Unit) {
    val c = LocalDabColors.current
    Row(Modifier.fillMaxSize().background(c.bg).padding(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DABplay+ · engine test", color = c.text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                    val sc = s.scan
                    Text(if (sc != null) "Scanning ${sc.channel} (${sc.index}/${sc.total}) · ${sc.ensembles} ensembles, ${sc.services} stations"
                         else "${s.status} · ${db.stations.size} stations, ${db.favourites.size} favourites",
                         color = c.neutral400, fontSize = 16.sp, maxLines = 2)
                }
                Box(Modifier.height(56.dp).background(Accent.blue, RoundedCornerShape(14.dp))
                    .combinedClickable(onClick = onScan).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text(if (s.scan != null) "Scanning…" else "Scan", color = Accent.onAccent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(db.stations, key = { it.key.toString() }) { st ->
                    val playing = st.key == s.current?.key
                    val fav = st.key in db.favourites
                    Box(
                        Modifier.height(64.dp).background(if (playing) Accent.orange.copy(alpha = .25f) else c.surface, RoundedCornerShape(14.dp))
                            .combinedClickable(onClick = { onPlay(st) }, onLongClick = { onFavourite(st) }).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text((if (fav) "♥ " else "") + st.label, color = if (playing) c.liveSub else c.text, fontSize = 20.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            val bmp = remember(s.slide) { s.slide?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
            Box(Modifier.size(420.dp, 315.dp).background(c.surface, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize())
                else Text("slideshow", color = c.neutral500, fontSize = 16.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.current?.label ?: "—", color = c.text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                    Text(s.nowPlaying?.display ?: "", color = c.neutral300, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("signal " + "▮".repeat(s.signalBars) + "▯".repeat(4 - s.signalBars), color = c.neutral500, fontSize = 14.sp)
                }
                Box(Modifier.size(80.dp).background(Brush.linearGradient(listOf(Accent.playFrom, Accent.playTo)), CircleShape)
                    .combinedClickable(onClick = onPlayPause), contentAlignment = Alignment.Center) {
                    Text(if (s.playing) "❚❚" else "▶", color = Accent.onAccent, fontSize = 28.sp)
                }
            }
        }
    }
}
