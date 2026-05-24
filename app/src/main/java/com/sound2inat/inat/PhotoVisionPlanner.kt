package com.sound2inat.inat

private const val RANK_SPECIES = 10
private const val RANK_GENUS = 20

enum class PhotoVisionTarget {
    SPECIES,
    GENUS,
    FAMILY,
}

data class PhotoVisionSuggestion(
    val taxonId: Long,
    val scientificName: String,
    val commonName: String?,
    val rank: String,
    val rankLevel: Int,
    val score: Double,
    val iconicTaxonName: String?,
    val photoUrl: String? = null,
)

data class PhotoVisionLadder(
    val topCandidates: List<PhotoVisionSuggestion> = emptyList(),
    val higherTaxa: List<PhotoVisionSuggestion> = emptyList(),
)

object PhotoVisionPlanner {
    fun collectAncestorIds(response: InatVisionResponse): List<Long> {
        if (response.candidates.isEmpty()) return emptyList()
        val ids = linkedSetOf<Long>()
        response.candidates.take(10).forEach { candidate ->
            candidate.ancestry.split('/').forEach { raw ->
                val id = raw.toLongOrNull() ?: return@forEach
                ids.add(id)
            }
            ids.add(candidate.taxonId)
        }
        return ids.toList()
    }

    fun buildLadder(
        response: InatVisionResponse,
        taxonInfo: Map<Long, InatTaxonInfo>,
    ): PhotoVisionLadder {
        val candidates = response.candidates
            .sortedByDescending(InatVisionCandidate::score)
            .take(10)

        if (candidates.isEmpty()) {
            return PhotoVisionLadder()
        }

        val topCandidates = candidates
            .filter { it.rankLevel <= RANK_GENUS }
            .take(3)
            .map { candidate ->
                candidate.toSuggestion(photoUrl = taxonInfo[candidate.taxonId]?.photoUrl)
            }

        val ancestorScores = linkedMapOf<Long, Double>()
        candidates.forEach { candidate ->
            candidate.ancestry.split('/')
                .mapNotNull(String::toLongOrNull)
                .forEach { id ->
                    ancestorScores[id] = (ancestorScores[id] ?: 0.0) + candidate.score
                }
        }

        val higherTaxa = ancestorScores.entries.mapNotNull { (id, score) ->
            val info = taxonInfo[id] ?: return@mapNotNull null
            if (info.rank != "genus" && info.rank != "family") return@mapNotNull null
            PhotoVisionSuggestion(
                taxonId = id,
                scientificName = info.scientificName,
                commonName = info.commonName,
                rank = info.rank,
                rankLevel = info.rankLevel,
                score = score,
                iconicTaxonName = info.iconicTaxonName,
                photoUrl = info.photoUrl,
            )
        }.sortedWith(
            compareBy<PhotoVisionSuggestion> { if (it.rank == "family") 0 else 1 }
                .thenByDescending { it.score }
        ).take(3)

        return PhotoVisionLadder(
            topCandidates = topCandidates,
            higherTaxa = higherTaxa,
        )
    }

    fun chooseSuggestion(
        ladder: PhotoVisionLadder,
        target: PhotoVisionTarget,
    ): PhotoVisionSuggestion? = when (target) {
        PhotoVisionTarget.SPECIES -> ladder.topCandidates.firstOrNull { it.rankLevel <= RANK_SPECIES }
            ?: ladder.topCandidates.firstOrNull()
        PhotoVisionTarget.GENUS -> ladder.higherTaxa.firstOrNull { it.rank == "genus" }
        PhotoVisionTarget.FAMILY -> ladder.higherTaxa.firstOrNull { it.rank == "family" }
    }

    private fun InatVisionCandidate.toSuggestion(photoUrl: String? = null): PhotoVisionSuggestion =
        PhotoVisionSuggestion(
            taxonId = taxonId,
            scientificName = scientificName,
            commonName = commonName,
            rank = rank,
            rankLevel = rankLevel,
            score = score,
            iconicTaxonName = iconicTaxonName,
            photoUrl = photoUrl,
        )
}
