package com.sound2inat.inat

import javax.inject.Inject

/**
 * Encapsulates the iNaturalist computer-vision suggestion flow:
 * score an already-uploaded observation, fetch ancestor taxon info,
 * and build the suggestion ladder for the Review screen.
 */
open class PhotoVisionUseCase @Inject constructor(
    private val client: INaturalistClient,
) {
    open suspend fun scoreSuggestions(token: String, observationId: Long): PhotoVisionLadder {
        val response = client.scoreObservationVision(token, observationId)
        val ancestorIds = PhotoVisionPlanner.collectAncestorIds(response)
        val taxonInfo = client.getTaxa(ancestorIds)
        return PhotoVisionPlanner.buildLadder(response, taxonInfo)
    }
}
