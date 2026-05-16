package com.sound2inat.app.ui.review

internal fun ReviewSettingsTab.appliesImmediately(): Boolean =
    this == ReviewSettingsTab.Visual

internal fun ReviewSettingsTab.showsConfirmationButtons(): Boolean =
    this == ReviewSettingsTab.Audio
