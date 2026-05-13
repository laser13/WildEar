package com.sound2inat.app.nav

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object Routes {
    const val HOME = "home"
    const val PHOTOS = "photos"
    const val RADAR = "radar"
    const val RECORDING = "recording"
    const val REVIEW = "review/{draftId}"
    const val PHOTO_CAPTURE = "photo_capture?draftId={draftId}"
    const val PHOTO_REVIEW = "photo_review/{photoDraftId}"
    const val SETTINGS = "settings"
    fun review(draftId: String) = "review/${encodeRouteArg(draftId)}"
    fun photoCapture(draftId: String? = null): String =
        draftId?.let { "photo_capture?draftId=${encodeRouteArg(it)}" } ?: "photo_capture"
    fun photoReview(photoDraftId: String): String = "photo_review/${encodeRouteArg(photoDraftId)}"

    private fun encodeRouteArg(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}

data class NavigationPopPolicy(
    val popUpToRoute: String,
    val inclusive: Boolean,
    val launchSingleTop: Boolean,
)

object NavigationPolicies {
    val PHOTO_CAPTURE_DONE = NavigationPopPolicy(
        popUpToRoute = Routes.PHOTO_CAPTURE,
        inclusive = true,
        launchSingleTop = true,
    )
}
