package com.sound2inat.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.sound2inat.app.ui.theme.LocalIsDarkTheme
import com.sound2inat.app.ui.theme.detectionCardLikelyDark
import com.sound2inat.app.ui.theme.detectionCardLikelyLight
import com.sound2inat.app.ui.theme.detectionCardUnlikelyDark
import com.sound2inat.app.ui.theme.detectionCardUnlikelyLight

/**
 * Background tint for a detected-species card. [likely] selects the warm
 * green "confirmed/likely" tint; otherwise the muted red "unlikely" tint.
 * Light/dark variants are chosen via [LocalIsDarkTheme] (not surface
 * luminance, which mis-classifies dynamic-colour schemes).
 */
@Composable
fun detectionCardColor(likely: Boolean): Color {
    val dark = LocalIsDarkTheme.current
    return if (likely) {
        if (dark) detectionCardLikelyDark else detectionCardLikelyLight
    } else {
        if (dark) detectionCardUnlikelyDark else detectionCardUnlikelyLight
    }
}
