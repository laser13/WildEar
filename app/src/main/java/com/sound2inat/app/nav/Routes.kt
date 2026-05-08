package com.sound2inat.app.nav

object Routes {
    const val HOME = "home"
    const val RADAR = "radar"
    const val RECORDING = "recording"
    const val REVIEW = "review/{draftId}"
    const val SETTINGS = "settings"
    fun review(draftId: String) = "review/$draftId"
}
