package com.sound2inat.app.data

import com.sound2inat.inat.LegacyInatTokenSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges [Settings] (app-layer DataStore) to the narrow [LegacyInatTokenSource]
 * interface expected by [com.sound2inat.inat.INatAuthRepository]. Delegates
 * every call 1:1 to the underlying [Settings] instance.
 *
 * Lives in `:app` so that `inat` stays independent of the app layer.
 */
@Singleton
class SettingsLegacyInatTokenSource @Inject constructor(
    private val settings: Settings,
) : LegacyInatTokenSource {
    override val inatToken: Flow<String?> get() = settings.inatToken
    override val inatLogin: Flow<String?> get() = settings.inatLogin
    override suspend fun setInatToken(value: String?) = settings.setInatToken(value)
    override suspend fun setInatLogin(value: String?) = settings.setInatLogin(value)
}
