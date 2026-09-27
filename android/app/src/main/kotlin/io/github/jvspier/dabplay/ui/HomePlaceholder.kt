package io.github.jvspier.dabplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jvspier.dabplay.ui.theme.Accent
import io.github.jvspier.dabplay.ui.theme.DabPlayTheme
import io.github.jvspier.dabplay.ui.theme.DisplayMode
import io.github.jvspier.dabplay.ui.theme.LocalDabColors

/** Skeleton placeholder of layout 1a (rail + favourites grid + mini player). Real screens come later. */
@Composable
fun HomePlaceholder() {
    val c = LocalDabColors.current
    Row(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier.width(120.dp).fillMaxHeight().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("14:32", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            listOf(Accent.blue, Accent.amber, Accent.orange, Accent.green, Accent.lavender).forEachIndexed { i, col ->
                Box(
                    Modifier.size(92.dp).background(if (i == 0) col else Color.Transparent, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(42.dp).background(if (i == 0) Accent.onAccent else col.copy(alpha = .7f), RoundedCornerShape(10.dp))) }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 24.dp, end = 24.dp, bottom = 16.dp)) {
            Text("Favorites", color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(2) { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(4) { col ->
                            val playing = row == 0 && col == 0
                            Column(
                                Modifier.weight(1f).fillMaxHeight()
                                    .background(c.surface, RoundedCornerShape(14.dp))
                                    .then(if (playing) Modifier.border(2.dp, Accent.orange, RoundedCornerShape(14.dp)) else Modifier)
                                    .padding(18.dp),
                            ) {
                                Box(Modifier.size(80.dp).background(c.neutral800, RoundedCornerShape(10.dp)))
                                Spacer(Modifier.weight(1f))
                                Text("Station ${row * 4 + col + 1}", color = if (playing) c.liveSub else c.text, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().height(104.dp).background(c.surface, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(80.dp).background(c.neutral800, RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("DABplay+", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    Text("Artist – Title", color = c.neutral400, fontSize = 18.sp)
                }
                Box(
                    Modifier.size(80.dp).background(Brush.linearGradient(listOf(Accent.playFrom, Accent.playTo)), CircleShape),
                )
            }
        }
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun HomePlaceholderPreview() = DabPlayTheme(DisplayMode.NIGHT) { HomePlaceholder() }
