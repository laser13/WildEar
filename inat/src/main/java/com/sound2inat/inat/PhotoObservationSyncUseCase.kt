package com.sound2inat.inat

import android.util.Log
import com.sound2inat.storage.PhotoDraftRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a batch sync: rows updated, and lookups that errored (no-taxon is neither). */
data class PhotoSyncResult(val synced: Int, val failed: Int)

/**
 * Pulls the current iNaturalist taxon for already-uploaded photo observations and
 * persists it locally so the Photos list reflects community identifications.
 *
 * Singleton: the [permits] semaphore is process-wide so concurrent callers (the
 * pull-to-refresh batch) cannot collectively exceed the iNat rate budget.
 */
@Singleton
open class PhotoObservationSyncUseCase @Inject constructor(
    private val client: INaturalistClient,
    private val repo: PhotoDraftRepository,
) {
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    constructor(
        client: INaturalistClient,
        repo: PhotoDraftRepository,
        ioDispatcher: CoroutineDispatcher,
    ) : this(client, repo) {
        this.ioDispatcher = ioDispatcher
    }

    private val permits = Semaphore(MAX_CONCURRENCY)

    private enum class SyncOutcome { SYNCED, NO_TAXON, ERROR }

    /**
     * Syncs each (draftId, observationId) pair concurrently (bounded by [permits]).
     * Best-effort: an individual lookup/persist failure is logged and counted as a
     * failure, never aborting the batch. [CancellationException] is rethrown so the
     * caller can cancel the whole run. A target whose observation has no iNat taxon
     * yet counts as neither synced nor failed.
     */
    open suspend fun syncAll(targets: List<Pair<String, Long>>): PhotoSyncResult = withContext(ioDispatcher) {
        if (targets.isEmpty()) return@withContext PhotoSyncResult(0, 0)
        val outcomes = coroutineScope {
            targets.map { (draftId, observationId) ->
                async { permits.withPermit { syncOne(draftId, observationId) } }
            }.awaitAll()
        }
        PhotoSyncResult(
            synced = outcomes.count { it == SyncOutcome.SYNCED },
            failed = outcomes.count { it == SyncOutcome.ERROR },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun syncOne(draftId: String, observationId: Long): SyncOutcome =
        try {
            val detail = client.getObservation(observationId.toString())
            if (detail.taxonName != null) {
                repo.updateSyncedTaxon(draftId, detail.taxonName, detail.taxonCommonName)
                SyncOutcome.SYNCED
            } else {
                SyncOutcome.NO_TAXON
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync failed for observation $observationId", e)
            SyncOutcome.ERROR
        }

    companion object {
        private const val TAG = "PhotoObsSync"
        private const val MAX_CONCURRENCY = 4
    }
}
