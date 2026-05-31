package com.sound2inat.inat

import kotlinx.coroutines.flow.Flow

/**
 * Narrow read/write contract for the legacy plain-DataStore iNat credentials
 * that older app versions stored unencrypted. Used exclusively by
 * [INatAuthRepository.readLegacyToken] / [INatAuthRepository.clearLegacyToken]
 * during the one-time migration to [INatTokenStorage].
 *
 * Keeping this interface in the `inat` package lets [INatAuthRepository] remain
 * independent of `com.sound2inat.app` — the concrete implementation lives in
 * `:app` and is wired via Hilt.
 */
interface LegacyInatTokenSource {
    val inatToken: Flow<String?>
    val inatLogin: Flow<String?>
    suspend fun setInatToken(value: String?)
    suspend fun setInatLogin(value: String?)
}
