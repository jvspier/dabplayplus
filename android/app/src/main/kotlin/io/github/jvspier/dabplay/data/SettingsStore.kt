package io.github.jvspier.dabplay.data

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class DisplayMode { DAY, AUTO, NIGHT }
enum class LayoutVariant { BAR, PANEL }          // design 1a (mini player bar) / 1b (docked player panel)
enum class DriverSide { LEFT, RIGHT }
enum class ClockFormat { SYSTEM, H12, H24 }      // SYSTEM follows Android's 12/24-hour setting

data class Settings(
    val display: DisplayMode = DisplayMode.AUTO,
    val layout: LayoutVariant = LayoutVariant.BAR,
    val driverSide: DriverSide = defaultDriverSide(),
    val resumeOnStart: Boolean = true,
    val downloadLogos: Boolean = true,
    val followStation: Boolean = true,
    val clock: ClockFormat = ClockFormat.SYSTEM,
    /** Switch to traffic announcements on the same multiplex (off by default: owner, 2026-09-28). Alarms always. */
    val trafficAnnouncements: Boolean = false,
    /**
     * Open DABplay+ when the car starts (the dongle appearing, or the boot). Off by default (owner, 2026-10-01): many
     * drivers start with CarPlay / Android Auto, and the radio shouldn't take over the screen.
     */
    val startWithCar: Boolean = false,
)

/** Countries that drive on the left, so the driver sits on the right and the controls belong on the right. */
private val RIGHT_HAND_DRIVE = setOf("GB", "IE", "MT", "CY", "AU", "NZ", "JP", "IN", "ZA", "HK", "SG", "MY", "TH", "ID")

fun defaultDriverSide(country: String = Locale.getDefault().country) =
    if (country.uppercase() in RIGHT_HAND_DRIVE) DriverSide.RIGHT else DriverSide.LEFT

/** App settings in SharedPreferences, exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state

    fun update(f: (Settings) -> Settings) {
        _state.update(f)
        val s = _state.value
        prefs.edit().putString("display", s.display.name).putString("layout", s.layout.name)
            .putString("driverSide", s.driverSide.name).putBoolean("resumeOnStart", s.resumeOnStart)
            .putBoolean("downloadLogos", s.downloadLogos).putBoolean("followStation", s.followStation)
            .putString("clock", s.clock.name).putBoolean("trafficAnnouncements", s.trafficAnnouncements)
            .putBoolean("startWithCar", s.startWithCar).apply()
    }

    private fun load(): Settings {
        val d = Settings()
        fun <E : Enum<E>> e(key: String, def: E, parse: (String) -> E) = prefs.getString(key, null)?.let { runCatching { parse(it) }.getOrNull() } ?: def
        return Settings(
            display = e("display", d.display, DisplayMode::valueOf),
            layout = e("layout", d.layout, LayoutVariant::valueOf),
            driverSide = e("driverSide", d.driverSide, DriverSide::valueOf),
            resumeOnStart = prefs.getBoolean("resumeOnStart", d.resumeOnStart),
            downloadLogos = prefs.getBoolean("downloadLogos", d.downloadLogos),
            followStation = prefs.getBoolean("followStation", d.followStation),
            clock = e("clock", d.clock, ClockFormat::valueOf),
            trafficAnnouncements = prefs.getBoolean("trafficAnnouncements", d.trafficAnnouncements),
            startWithCar = prefs.getBoolean("startWithCar", d.startWithCar),
        )
    }
}
