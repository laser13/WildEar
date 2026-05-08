package com.sound2inat.inat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaxonPhotoRepository(
    private val fetchUrl: suspend (String) -> String?,
) {

    @Inject constructor(client: INaturalistClient) : this(
        fetchUrl = client::fetchTaxonPhotoUrl,
    )

    private val lru = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(e: Map.Entry<String, String?>) = size > MAX_ENTRIES
    }
    private val mutex = Mutex()

    fun observe(taxonName: String): Flow<String?> = flow {
        val (hit, url) = mutex.withLock {
            if (lru.containsKey(taxonName)) true to lru[taxonName] else false to null
        }
        if (hit) {
            emit(url)
            return@flow
        }
        emit(null)
        val fetched = runCatching { fetchUrl(taxonName) }.getOrNull()
        mutex.withLock { lru[taxonName] = fetched }
        emit(fetched)
    }

    companion object {
        const val MAX_ENTRIES = 256
    }
}
