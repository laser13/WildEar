package com.sound2inat.inat

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but rethrows [CancellationException] so structured
 * concurrency works correctly. Used by both [INatSubmitter] and
 * [PhotoSubmitter] in their best-effort wrappers, where a swallowed
 * cancel would prevent the viewModelScope teardown from propagating.
 */
internal inline fun <R> runCatchingNonCancellation(block: () -> R): kotlin.Result<R> = try {
    kotlin.Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    kotlin.Result.failure(t)
}
