package com.sound2inat.inat

data class InatVisionResponse(
    val candidates: List<InatVisionCandidate>,
    val commonAncestor: InatVisionTaxon? = null,
)

data class InatVisionCandidate(
    val taxonId: Long,
    val scientificName: String,
    val commonName: String?,
    val rank: String,
    val rankLevel: Int,
    val score: Double,
    val ancestry: String,
    val iconicTaxonName: String?,
)

data class InatVisionTaxon(
    val taxonId: Long,
    val scientificName: String,
    val commonName: String?,
    val rank: String,
    val rankLevel: Int,
)

data class InatTaxonInfo(
    val scientificName: String,
    val commonName: String?,
    val rank: String,
    val rankLevel: Int,
    val iconicTaxonName: String?,
)
