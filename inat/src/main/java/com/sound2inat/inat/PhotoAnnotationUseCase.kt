package com.sound2inat.inat

import javax.inject.Inject

/**
 * Encapsulates the iNaturalist identification + annotation flow:
 * adds a taxon identification vote on an observation, then attaches
 * controlled-term annotations (alive/dead, evidence, life stage) when
 * the suggested taxon belongs to an animal iconic group.
 */
open class PhotoAnnotationUseCase @Inject constructor(
    private val client: INaturalistClient,
) {
    /**
     * Adds identification for [suggestion] on [observationId], then applies
     * animal-specific annotations on [observationUuid] if applicable.
     *
     * Annotation failures are propagated as exceptions; callers decide whether
     * to treat them as warnings or hard errors.
     */
    open suspend fun addIdentification(
        token: String,
        observationId: Long,
        suggestion: PhotoVisionSuggestion,
    ) {
        client.addIdentification(
            token = token,
            observationId = observationId,
            taxonId = suggestion.taxonId,
            body = null,
        )
    }

    /**
     * Applies animal-specific annotations to [observationUuid].
     * Does nothing if the suggestion's [PhotoVisionSuggestion.iconicTaxonName]
     * is not in [ANIMAL_ICONIC_TAXA].
     */
    open suspend fun applyAnnotations(
        token: String,
        observationUuid: String,
        suggestion: PhotoVisionSuggestion,
    ) {
        val iconic = suggestion.iconicTaxonName.orEmpty()
        if (iconic !in ANIMAL_ICONIC_TAXA) return

        client.createAnnotation(
            token = token,
            observationUuid = observationUuid,
            controlledAttributeId = InatAnnotationIds.ATTR_ALIVE_OR_DEAD,
            controlledValueId = InatAnnotationIds.VAL_ALIVE,
        )
        client.createAnnotation(
            token = token,
            observationUuid = observationUuid,
            controlledAttributeId = InatAnnotationIds.ATTR_EVIDENCE_OF_PRESENCE,
            controlledValueId = InatAnnotationIds.VAL_ORGANISM,
        )
        if (iconic == "Insecta") {
            client.createAnnotation(
                token = token,
                observationUuid = observationUuid,
                controlledAttributeId = InatAnnotationIds.ATTR_LIFE_STAGE,
                controlledValueId = InatAnnotationIds.VAL_ADULT,
            )
        }
    }
}
