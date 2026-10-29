package io.github.jvspier.dabplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.jvspier.dabplay.core.pad.NowPlaying
import io.github.jvspier.dabplay.data.DisplayMode
import io.github.jvspier.dabplay.data.LayoutVariant
import io.github.jvspier.dabplay.data.LogoStore
import io.github.jvspier.dabplay.data.Recent
import io.github.jvspier.dabplay.data.ServiceKey
import io.github.jvspier.dabplay.data.Settings
import io.github.jvspier.dabplay.data.Snapshot
import io.github.jvspier.dabplay.data.StoredEnsemble
import io.github.jvspier.dabplay.data.StoredService
import io.github.jvspier.dabplay.engine.EngineState
import io.github.jvspier.dabplay.ui.AppShell
import io.github.jvspier.dabplay.ui.RadioActions
import io.github.jvspier.dabplay.ui.components.LocalLogos
import io.github.jvspier.dabplay.ui.theme.DabPlayTheme
import java.io.File

/**
 * Debug builds only (src/debug): the real UI with made-up stations, playing, for store screenshots without
 * broadcasters' names or logos. No dongle, no engine: AppShell gets a hand-made EngineState and Snapshot, and the
 * made-up logos (assets/demo-logos, from tools/branding/demo-logos.html) stand in for downloaded ones.
 *
 *     adb shell am start -n io.github.jvspier.dabplay/.DemoActivity --es theme night|day [--es layout panel]
 */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val display = if (intent.getStringExtra("theme") == "day") DisplayMode.DAY else DisplayMode.NIGHT
        val logos = demoLogos()
        setContent {
            val layout = if (intent.getStringExtra("layout") == "panel") LayoutVariant.PANEL else LayoutVariant.BAR
            var settings by remember { mutableStateOf(Settings(display = display, layout = layout)) }
            var playingKey by remember { mutableStateOf(STATIONS[1].key) }
            var playing by remember { mutableStateOf(true) }
            val db = SNAPSHOT
            val station = db.stations.first { it.key == playingKey }
            val engine = EngineState(dongle = true, status = "Playing ${station.label}", current = station, playing = playing,
                nowPlaying = SONGS[playingKey], signalBars = 4, taSupported = playingKey == STATIONS[6].key)
            val actions = RadioActions(
                play = { playingKey = it; playing = true }, togglePlay = { playing = !playing },
                skip = { d -> val f = db.favourites; playingKey = f[Math.floorMod(f.indexOf(playingKey) + d, f.size)] },
                toggleFavourite = {}, moveFavourite = { _, _ -> }, scan = {}, clearRecents = { emptyList() }, restoreRecents = {},
                updateSettings = { f -> settings = f(settings) },
            )
            DabPlayTheme(settings.display, settings.background, settings.pureBlackAtNight) {
                CompositionLocalProvider(LocalLogos provides logos) {
                    AppShell(engine, db, settings, actions)
                }
            }
        }
    }

    /** The made-up logos as if downloaded: copied out of the assets once, one per station (file named after it). */
    private fun demoLogos(): Map<ServiceKey, LogoStore.Entry> {
        val dir = File(filesDir, "demo-logos").apply { mkdirs() }
        return STATIONS.mapNotNull { s ->
            val name = s.label.lowercase().replace(' ', '-') + ".png"
            val f = File(dir, name)
            runCatching { if (!f.exists()) assets.open("demo-logos/$name").use { i -> f.outputStream().use { i.copyTo(it) } } }
                .getOrNull() ?: return@mapNotNull null
            s.key to LogoStore.Entry(f, "DEMO", "", 1L)
        }.toMap()
    }

    private companion object {
        fun svc(sid: Long, label: String, sub: Int) = StoredService(ServiceKey(0xE3, sid), label, sub, 96, 63)

        val NORTH = StoredEnsemble(0x8101, "11C", 220352, "Northline", 0, 0, listOf(
            svc(0x8A01, "Lumen Radio", 1), svc(0x8A02, "Night Drive", 2), svc(0x8A03, "Velvet Jazz", 3),
            svc(0x8A04, "Harbour Hits", 4), svc(0x8A05, "Echo Rock", 5), svc(0x8A06, "Atlas News", 6)))
        val SOUTH = StoredEnsemble(0x8102, "12C", 227360, "Southwave", 0, 0, listOf(
            svc(0x8B01, "Sunset Classics", 1), svc(0x8B02, "Metro Talk", 2), svc(0x8B03, "Ember Beats", 3),
            svc(0x8B04, "Polar Pop", 4), svc(0x8B05, "Coastline 80s", 5), svc(0x8B06, "Dune Dance", 6)))
        val STATIONS = NORTH.services + SOUTH.services

        val SNAPSHOT = Snapshot(
            ensembles = listOf(NORTH, SOUTH),
            favourites = listOf(0, 1, 2, 3, 6, 4, 9, 7).map { STATIONS[it].key },
            recents = listOf(1, 3, 6, 9, 2).mapIndexed { i, s -> Recent(STATIONS[s].key, System.currentTimeMillis() - (i + 1) * 23 * 60_000L) },
            lastPlayed = STATIONS[1].key,
        )

        val SONGS: Map<ServiceKey, NowPlaying> = mapOf(
            0 to ("The Paper Lanterns" to "Open Road"), 1 to ("Midnight Avenue" to "City Lights"), 2 to ("Clara Wells Trio" to "Blue Hour"),
            3 to ("Northbound" to "Summer Signal"), 4 to ("Iron Harbour" to "Static Hearts"), 6 to ("The Sundowners" to "Golden Mile"),
            7 to ("" to ""), 9 to ("Neon Coast" to "Electric Tide"),
        ).mapNotNull { (i, s) ->
            if (s.first.isEmpty()) null else STATIONS[i].key to NowPlaying("${s.first} - ${s.second}", s.first, s.second)
        }.toMap()
    }
}
