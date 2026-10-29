package io.github.jvspier.dabplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/**
 * The design's size for this window. DABplay+ was designed on a 1280×720 dp head unit (the Dudu3), whose height is
 * Android's "medium" window size class. Android's way to serve other sizes is a dimension set per size class (what
 * values-h480dp / values-h900dp resources used to be): here every dimension of the design at [factor]. Smaller on a
 * compact-height window (under 480 dp tall: a phone in landscape), bigger on an expanded one (900 dp and up: large
 * tablets and head units), and exactly as designed in between. Parts that must always fit (the rail, the tiles, the
 * side panel) also size themselves from the space they get, which covers the sizes within a class.
 */
enum class UiScale(val factor: Float) { COMPACT(0.75f), REGULAR(1f), LARGE(1.25f) }

/** [UiScale] from the window's height class (androidx.window's breakpoints). */
fun uiScaleFor(widthDp: Float, heightDp: Float): UiScale {
    val cls = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp, heightDp)
    return when {
        !cls.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND) -> UiScale.COMPACT
        cls.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_EXPANDED_LOWER_BOUND) -> UiScale.LARGE
        else -> UiScale.REGULAR
    }
}

/**
 * Draws [content] at the window's [UiScale]: every dp and sp inside is the design's, times the factor. The window's
 * size (not the content's) decides, so showing or hiding the system bars never changes it.
 */
@Composable
fun AdaptiveSizing(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    // before the window has a size (the very first frame), the design as is
    val scale = if (window.height == 0) UiScale.REGULAR else uiScaleFor(window.width / density.density, window.height / density.density)
    if (scale == UiScale.REGULAR) content()
    else CompositionLocalProvider(LocalDensity provides Density(density.density * scale.factor, density.fontScale), content = content)
}
