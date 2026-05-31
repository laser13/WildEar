package com.sound2inat.app.ui.review

import java.io.File

/**
 * iNat submission seam abstracted from the production [com.sound2inat.inat.INatSubmitter]
 * so the ViewModel/coordinator are unit-testable without going near OkHttp.
 * Moved here from ReviewViewModel.kt as part of Phase 2 decomposition.
 */
fun interface InatSubmissionJob {
    suspend fun submit(
        token: String,
        draftId: String,
        habitatPhotos: List<File>,
        includeHabitatPhotoByTaxon: Map<String, Boolean>,
        sourceAudioOverride: File?,
        onProgress: (com.sound2inat.inat.SubmissionProgress) -> Unit,
    ): InatSubmissionOutcome
}

sealed interface InatSubmissionOutcome {
    data class Success(val urls: List<String>) : InatSubmissionOutcome
    data class Failure(val message: String) : InatSubmissionOutcome
}

/** Result of [ReviewSubmissionCoordinator.resolveToken]. */
sealed interface TokenResolution {
    /** A usable token is available. */
    data class Ready(val token: String) : TokenResolution

    /** No token; the UI should launch interactive login (first attempt). */
    data object NeedsInteractiveLogin : TokenResolution

    /** No token and interactive login was already tried — surface a terminal error. */
    data object Expired : TokenResolution
}

/**
 * Owns iNat OAuth token resolution, the interactive re-login guard, and the
 * [InatSubmissionJob] invocation. Holds the [interactiveLoginAttempted] flag
 * extracted from ReviewViewModel; it is [Volatile] so writes from coroutines on
 * different dispatchers are visible (closes the data race on the old plain
 * `var` at ReviewViewModel.kt:770).
 *
 * This class deliberately does NOT own `_state`; the ViewModel folds the
 * coordinator's results into UI state so the existing characterization tests,
 * which assert on `vm.state`, keep passing.
 */
class ReviewSubmissionCoordinator(
    private val tokenProvider: suspend () -> String?,
    private val acceptInatToken: suspend (String) -> Unit,
    private val submission: InatSubmissionJob,
) {
    @Volatile
    private var interactiveLoginAttempted: Boolean = false

    fun markInteractiveLoginAttempted() { interactiveLoginAttempted = true }

    fun clearLoginGuard() { interactiveLoginAttempted = false }

    /**
     * Attempts to obtain a usable token. Mirrors the original branching in
     * submitToINaturalist: a blank/null token on the first try requests
     * interactive login; a second null after an interactive attempt is terminal.
     * Clears the guard before returning [TokenResolution.Expired] so a later
     * Submit can retry from scratch.
     */
    suspend fun resolveToken(): TokenResolution {
        val token = tokenProvider()
        if (!token.isNullOrBlank()) return TokenResolution.Ready(token)
        return if (interactiveLoginAttempted) {
            interactiveLoginAttempted = false
            TokenResolution.Expired
        } else {
            TokenResolution.NeedsInteractiveLogin
        }
    }

    /** Persists a token captured by interactive login. Delegates to acceptInatToken. */
    suspend fun acceptToken(token: String) = acceptInatToken(token)

    /** Runs the submission job. Pure forwarding so the VM stays thin. */
    suspend fun submit(
        token: String,
        draftId: String,
        habitatPhotos: List<File>,
        includeHabitatPhotoByTaxon: Map<String, Boolean>,
        sourceAudioOverride: File?,
        onProgress: (com.sound2inat.inat.SubmissionProgress) -> Unit,
    ): InatSubmissionOutcome = submission.submit(
        token = token,
        draftId = draftId,
        habitatPhotos = habitatPhotos,
        includeHabitatPhotoByTaxon = includeHabitatPhotoByTaxon,
        sourceAudioOverride = sourceAudioOverride,
        onProgress = onProgress,
    )
}
