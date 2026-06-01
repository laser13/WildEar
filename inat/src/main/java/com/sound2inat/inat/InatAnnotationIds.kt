package com.sound2inat.inat

object InatAnnotationIds {
    // Controlled attribute: "Alive or Dead"
    const val ATTR_ALIVE_OR_DEAD = 17
    const val VAL_ALIVE = 18

    // Controlled attribute: "Evidence of Presence"
    const val ATTR_EVIDENCE_OF_PRESENCE = 22
    const val VAL_ORGANISM = 24

    // Controlled attribute: "Life Stage"
    const val ATTR_LIFE_STAGE = 1
    const val VAL_ADULT = 2
    const val VAL_EGG = 4
    const val VAL_JUVENILE = 6
    const val VAL_LARVA = 7
    const val VAL_PUPA = 8
    const val VAL_NYMPH = 16
}

// iNaturalist's "iconic taxa" — the top-level groupings on a taxon's record.
// We accept anything that's a vocalising or audibly active organism;
// explicitly reject Plantae/Fungi/Protozoa/Chromista where a name collision
// would be silly (or worse, recorded under a wrong kingdom on the user's account).
val ANIMAL_ICONIC_TAXA: Set<String> = setOf(
    "Animalia", "Aves", "Mammalia", "Insecta", "Arachnida",
    "Reptilia", "Amphibia", "Mollusca", "Actinopterygii",
)
