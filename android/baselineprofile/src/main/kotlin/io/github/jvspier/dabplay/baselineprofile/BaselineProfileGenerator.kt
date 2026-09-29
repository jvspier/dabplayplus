package io.github.jvspier.dabplay.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a driver does in the first minutes: start, every tab, fling the station list, open Now playing. The code on
 * these paths is compiled at install. Works without a dongle (the UI is the same; the radio code is small and simple).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = "io.github.jvspier.dabplay", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        fun tab(name: String) { device.findObject(By.desc(name))?.click(); device.waitForIdle() }
        // looked up again each time: a fling recomposes the list and the old node goes stale
        fun fling(d: Direction) {
            runCatching { device.findObject(By.scrollable(true))?.fling(d) }
            device.waitForIdle()
        }
        tab("Stations")
        repeat(2) { fling(Direction.DOWN); fling(Direction.UP) }
        tab("Playing")
        tab("Recent")
        tab("Settings")
        fling(Direction.DOWN)
        tab("Home")
        tab("Playing")
        tab("Home")
    }
}
