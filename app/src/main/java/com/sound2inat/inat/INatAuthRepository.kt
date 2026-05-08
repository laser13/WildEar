package com.sound2inat.inat

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.sound2inat.app.data.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Single source of truth for the iNaturalist API token. Reads/writes go
 * through [INatTokenStorage] (encrypted), and the repository takes care
 * of three orthogonal concerns:
 *
 *  1. Reactive observation: `tokenState` / `loginState` are
 *     [StateFlow]s the Settings UI subscribes to.
 *  2. Lazy validity: [getValidToken] returns the cached token if it is
 *     younger than [TOKEN_TTL_MS]; otherwise it tries a silent refresh
 *     against `/users/api_token` (works while the WebView's iNat session
 *     cookie is still valid). If both fail, returns null and the caller
 *     surfaces an "interactive login required" UX.
 *  3. One-time migration: legacy plain-DataStore tokens (used in earlier
 *     versions) are imported into encrypted storage on first read, then
 *     scrubbed from DataStore.
 */
@Singleton
open class INatAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: INatTokenStore,
    private val settings: Settings,
    private val client: INaturalistClient,
) {
    private val _tokenState: MutableStateFlow<String?> = MutableStateFlow(storage.token)
    val tokenState: StateFlow<String?> = _tokenState.asStateFlow()

    private val _loginState: MutableStateFlow<String?> = MutableStateFlow(storage.login)
    val loginState: StateFlow<String?> = _loginState.asStateFlow()

    private val _userIdState: MutableStateFlow<Long?> = MutableStateFlow(storage.userId)
    val userIdState: StateFlow<Long?> = _userIdState.asStateFlow()

    /** Convenience: snapshot of [userIdState]. */
    val userId: Long? get() = _userIdState.value

    private val migrationMutex = Mutex()
    private var migrationChecked = false

    /**
     * Returns a usable api_token, attempting a silent refresh first if the
     * cached one is stale. `null` means the caller must launch interactive
     * login (see [INatWebLoginActivity]).
     */
    suspend fun getValidToken(refreshDispatcher: CoroutineDispatcher = Dispatchers.Main): String? {
        ensureMigrated()
        val cached = storage.token
        val age = System.currentTimeMillis() - storage.tokenFetchedAtUtcMs
        if (cached != null && age in 0..TOKEN_TTL_MS) return cached
        // Stale or missing: attempt silent WebView refresh. Return null on failure
        // so the caller surfaces an interactive login prompt instead of silently
        // sending a stale token that will 401.
        return trySilentRefresh(refreshDispatcher)
    }

    /**
     * Saves a token captured by [INatWebLoginActivity] (or a successful
     * silent refresh). Side-effect: queries `/users/me` for the login so
     * Settings can show "Logged in as @user". Failure to fetch the login
     * is non-fatal — token is saved either way.
     */
    suspend fun acceptCapturedToken(
        token: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        val pair = withContext(ioDispatcher) {
            runCatching { client.verifyTokenWithUser(token) }.getOrNull()
        }
        val login = pair?.first
        val userId = pair?.second
        storage.save(token, login, userId, System.currentTimeMillis())
        _tokenState.value = token
        _loginState.value = login
        _userIdState.value = userId
    }

    /**
     * Wipes encrypted storage, system WebView cookies, and JS-storage so
     * the next interactive login starts on a fresh iNat session.
     */
    suspend fun logout(mainDispatcher: CoroutineDispatcher = Dispatchers.Main) {
        storage.clear()
        _tokenState.value = null
        _loginState.value = null
        _userIdState.value = null
        withContext(mainDispatcher) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    /**
     * Override point for tests: replace with a stub that does not spin a WebView.
     * Production code calls [trySilentRefreshWebView].
     */
    internal open suspend fun trySilentRefresh(dispatcher: CoroutineDispatcher): String? =
        trySilentRefreshWebView(dispatcher)

    /**
     * Headless variant of [INatWebLoginActivity]'s flow — spins a hidden
     * [android.webkit.WebView] on the main thread, navigates to
     * `/users/api_token`, and waits for [parseTokenJson] to extract the
     * token. Returns null if the iNat session cookie has expired and the
     * page redirects to `/login` (no api_token JSON ever appears) — the
     * caller should fall back to interactive login.
     *
     * `WebView` requires a Looper, so the work is forced onto
     * [mainDispatcher] regardless of the caller's coroutine context.
     */
    private suspend fun trySilentRefreshWebView(mainDispatcher: CoroutineDispatcher): String? =
        withContext(mainDispatcher) {
            suspendCancellableCoroutine<String?> { cont ->
                var resolved = false
                val webView = buildWebView(
                    context = context,
                    onLoadingChange = { /* ignored — headless */ },
                    onTokenCaptured = { token ->
                        if (!resolved) {
                            resolved = true
                            cont.resume(token)
                        }
                    },
                )
                cont.invokeOnCancellation {
                    webView.stopLoading()
                    webView.destroy()
                }
                // After SILENT_REFRESH_TIMEOUT_MS we give up. iNat redirects
                // unauthenticated requests to /login, which never fires the
                // token capture callback — without a deadline we'd hang.
                webView.postDelayed({
                    if (!resolved) {
                        resolved = true
                        webView.destroy()
                        cont.resume(null)
                    }
                }, SILENT_REFRESH_TIMEOUT_MS)
            }
        }

    /**
     * Imports a legacy plain-DataStore token from earlier app versions
     * into encrypted storage exactly once per process. The DataStore copy
     * is cleared so a future leak only exposes the encrypted blob.
     *
     * Guarded by [migrationMutex] to prevent the check-then-act from racing
     * under concurrent coroutine calls (e.g. two simultaneous API requests).
     */
    private suspend fun ensureMigrated() = migrationMutex.withLock {
        if (migrationChecked) return@withLock
        migrationChecked = true
        if (storage.token != null) return@withLock
        val (legacy, legacyLogin) = readLegacyToken() ?: return@withLock
        // Mark fetched-at = "now" so the freshness check at least gives the
        // legacy token one normal TTL window before triggering refresh —
        // we don't actually know how old it was.
        storage.save(legacy, legacyLogin, null, System.currentTimeMillis())
        _tokenState.value = legacy
        _loginState.value = legacyLogin
        clearLegacyToken()
    }

    /** Override in tests to avoid DataStore dependency. */
    internal open suspend fun readLegacyToken(): Pair<String, String?>? {
        val legacy = runCatching { settings.inatToken.first() }.getOrNull() ?: return null
        if (legacy.isBlank()) return null
        return legacy to runCatching { settings.inatLogin.first() }.getOrNull()
    }

    /** Override in tests to avoid DataStore dependency. */
    internal open suspend fun clearLegacyToken() {
        runCatching {
            settings.setInatToken(null)
            settings.setInatLogin(null)
        }
    }

    internal companion object {
        /** iNat api_tokens are JWTs with a 24h `exp`; refresh just before. */
        const val TOKEN_TTL_MS = 23L * 60 * 60 * 1000

        /** Hard ceiling for one silent-refresh attempt. */
        const val SILENT_REFRESH_TIMEOUT_MS = 15_000L
    }
}
