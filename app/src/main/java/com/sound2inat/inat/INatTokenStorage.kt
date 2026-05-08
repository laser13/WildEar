package com.sound2inat.inat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal contract used by [INatAuthRepository]; allows in-memory fakes in unit tests. */
interface INatTokenStore {
    val token: String?
    val tokenFetchedAtUtcMs: Long
    val login: String?
    val userId: Long?
    fun save(token: String, login: String?, userId: Long?, fetchedAtUtcMs: Long)
    fun clear()
}

/**
 * Encrypted-at-rest storage for the iNaturalist API token + login. Uses
 * Jetpack `security-crypto` (`EncryptedSharedPreferences`, AES256-GCM, key
 * material in the Android Keystore) so a rooted device cannot pull the
 * token out of plain DataStore.
 *
 * Reads/writes are synchronous on purpose — `EncryptedSharedPreferences` is
 * fast enough for occasional access and async storage adds plumbing without
 * solving anything for this use case.
 */
@Singleton
class INatTokenStorage @Inject constructor(
    @ApplicationContext context: Context,
) : INatTokenStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Last token returned by `/users/api_token`, or null when absent. */
    override val token: String? get() = prefs.getString(KEY_TOKEN, null)?.takeIf(String::isNotBlank)

    /** Authenticated login (e.g. "@semen") if known, null otherwise. */
    override val login: String? get() = prefs.getString(KEY_LOGIN, null)?.takeIf(String::isNotBlank)

    /** Numeric iNaturalist user id, or null if unknown (e.g. legacy migration). */
    override val userId: Long? get() = prefs.getLong(KEY_USER_ID, -1L).takeIf { it > 0 }

    /** When [token] was last refreshed (epoch ms). 0 means "never". */
    override val tokenFetchedAtUtcMs: Long get() = prefs.getLong(KEY_FETCHED_AT, 0L)

    override fun save(token: String, login: String?, userId: Long?, fetchedAtUtcMs: Long) {
        prefs.edit().apply {
            putString(KEY_TOKEN, token)
            if (login != null) putString(KEY_LOGIN, login) else remove(KEY_LOGIN)
            if (userId != null && userId > 0) putLong(KEY_USER_ID, userId) else remove(KEY_USER_ID)
            putLong(KEY_FETCHED_AT, fetchedAtUtcMs)
            apply()
        }
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "inat_auth"
        const val KEY_TOKEN = "api_token"
        const val KEY_LOGIN = "login"
        const val KEY_USER_ID = "user_id"
        const val KEY_FETCHED_AT = "fetched_at_ms"
    }
}
