package com.sound2inat.inat

import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class INatAuthRepositoryTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun buildRepo(
        storage: FakeTokenStore = FakeTokenStore(),
        silentRefreshFn: suspend () -> String? = { null },
        legacySource: suspend () -> Pair<String, String?>? = { null },
    ): INatAuthRepository {
        val client = INaturalistClient(OkHttpClient())
        return object : INatAuthRepository(ctx, storage, NoOpLegacyInatTokenSource, client) {
            override suspend fun trySilentRefresh(dispatcher: CoroutineDispatcher) = silentRefreshFn()
            override suspend fun readLegacyToken(): Pair<String, String?>? = legacySource()
            override suspend fun clearLegacyToken() = Unit
        }
    }

    @Test
    fun `getValidToken returns cached token when fresh`() = runTest {
        val storage = FakeTokenStore(token = "valid-token", ageMs = 0L)
        val repo = buildRepo(storage = storage)
        assertThat(repo.getValidToken()).isEqualTo("valid-token")
    }

    @Test
    fun `getValidToken returns null when token is expired and refresh fails`() = runTest {
        val storage = FakeTokenStore(token = "old-token", ageMs = INatAuthRepository.TOKEN_TTL_MS + 1)
        val repo = buildRepo(storage = storage, silentRefreshFn = { null })
        assertThat(repo.getValidToken()).isNull()
    }

    @Test
    fun `getValidToken returns refreshed token when stale`() = runTest {
        val storage = FakeTokenStore(token = "old-token", ageMs = INatAuthRepository.TOKEN_TTL_MS + 1)
        val repo = buildRepo(storage = storage, silentRefreshFn = { "refreshed-token" })
        assertThat(repo.getValidToken()).isEqualTo("refreshed-token")
    }

    @Test
    fun `ensureMigrated runs at most once under concurrent calls`() = runTest {
        val migrationCalls = AtomicInteger(0)
        val repo = buildRepo(
            legacySource = {
                migrationCalls.incrementAndGet()
                null
            } // null = no legacy token
        )
        coroutineScope {
            repeat(50) { launch { repo.getValidToken() } }
        }
        assertThat(migrationCalls.get()).isEqualTo(1)
    }

    @Test
    fun `ensureMigrated promotes legacy token to encrypted storage`() = runTest {
        val storage = FakeTokenStore()
        val repo = buildRepo(
            storage = storage,
            legacySource = { Pair("legacy-token", "alice") }
        )
        repo.getValidToken()
        assertThat(storage.savedToken).isEqualTo("legacy-token")
        assertThat(storage.savedLogin).isEqualTo("alice")
    }
}

private object NoOpLegacyInatTokenSource : LegacyInatTokenSource {
    override val inatToken: Flow<String?> = flowOf(null)
    override val inatLogin: Flow<String?> = flowOf(null)
    override suspend fun setInatToken(value: String?) = Unit
    override suspend fun setInatLogin(value: String?) = Unit
}

private class FakeTokenStore(
    override var token: String? = null,
    ageMs: Long = 0L,
) : INatTokenStore {
    override val tokenFetchedAtUtcMs: Long = System.currentTimeMillis() - ageMs
    override var login: String? = null
    override var userId: Long? = null

    var savedToken: String? = null
    var savedLogin: String? = null

    override fun save(token: String, login: String?, userId: Long?, fetchedAtUtcMs: Long) {
        this.token = token
        this.login = login
        this.userId = userId
        savedToken = token
        savedLogin = login
    }

    override fun clear() {
        token = null
        login = null
        userId = null
    }
}
