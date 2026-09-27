package io.github.jvspier.dabplay.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jvspier.dabplay.engine.EngineState
import io.github.jvspier.dabplay.engine.Station
import io.github.jvspier.dabplay.ui.theme.Accent
import io.github.jvspier.dabplay.ui.theme.LocalDabColors

/** Temporary hardware-test screen: station buttons, now playing, current slide. Not the real UI. */
@Composable
fun RadioTestScreen(s: EngineState, onPlay: (Station) -> Unit) {
    val c = LocalDabColors.current
    Row(Modifier.fillMaxSize().background(c.bg).padding(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text("DABplay+ · radio test", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.Medium)
            Text(s.status, color = c.neutral400, fontSize = 18.sp, maxLines = 2)
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(s.stations) { st ->
                    val playing = st == s.playing
                    Box(
                        Modifier.height(72.dp).background(if (playing) Accent.orange.copy(alpha = .25f) else c.surface, RoundedCornerShape(14.dp))
                            .clickable { onPlay(st) }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text(st.label, color = if (playing) c.liveSub else c.text, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
            Spacer(Modifier.height(20.dp))
            Text(s.playing?.label ?: "—", color = c.text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Text(s.nowPlaying ?: "", color = c.neutral300, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(s.info ?: "", color = c.neutral500, fontSize = 14.sp)
        }
    }
}
