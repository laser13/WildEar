package com.sound2inat.app.ui.radar

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

internal fun Context.openCustomTab(url: String) {
    CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
}
