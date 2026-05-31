package com.sound2inat.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sound2inat.app.R
import com.sound2inat.app.ui.theme.LocalIsDarkTheme

/**
 * Shared top app bar for the top-level browsing tabs.
 *
 * @param title shown when [showLogo] is false (Photos, Radar).
 * @param showLogo when true, renders the app logo as the navigation icon
 *   instead of a text title (Home).
 * @param inlineContent optional slot prepended to the actions area (rendered
 *   to the right of the title, before [actions]), e.g. Home's filter chips.
 *   Receives the actions RowScope.
 * @param actions trailing action slot (e.g. a settings IconButton).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sound2iNatTopBar(
    title: String,
    modifier: Modifier = Modifier,
    showLogo: Boolean = false,
    inlineContent: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            if (showLogo) {
                val logoRes = if (LocalIsDarkTheme.current) {
                    R.drawable.ic_app_logo_dark
                } else {
                    R.drawable.ic_app_logo_light
                }
                Image(
                    painter = painterResource(logoRes),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(32.dp)
                        .clip(CircleShape),
                )
            } else {
                Text(title)
            }
        },
        actions = {
            inlineContent?.invoke(this)
            actions()
        },
    )
}
