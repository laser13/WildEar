@file:Suppress("MatchingDeclarationName")

package com.sound2inat.app.ui.radar

import androidx.annotation.DrawableRes
import com.sound2inat.app.R

/**
 * iNat iconic-taxon descriptor. The [id] matches the string iNat returns
 * in `iconic_taxon_name`, so mapping API → icon is a direct lookup.
 */
data class IconicTaxon(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int,
)

/**
 * Eleven taxa filterable via iNat's `iconic_taxa` query parameter. "Unknown"
 * is intentionally not in this list because iNat has no `iconic_taxa=Unknown`
 * value — observations without an identification simply have a null
 * `iconic_taxon_name`.
 */
internal val FilterableIconicTaxa: List<IconicTaxon> = listOf(
    IconicTaxon("Aves", "Birds", R.drawable.ic_iconic_aves),
    IconicTaxon("Amphibia", "Amphibians", R.drawable.ic_iconic_amphibia),
    IconicTaxon("Reptilia", "Reptiles", R.drawable.ic_iconic_reptilia),
    IconicTaxon("Mammalia", "Mammals", R.drawable.ic_iconic_mammalia),
    IconicTaxon("Actinopterygii", "Fish", R.drawable.ic_iconic_actinopterygii),
    IconicTaxon("Mollusca", "Molluscs", R.drawable.ic_iconic_mollusca),
    IconicTaxon("Arachnida", "Arachnids", R.drawable.ic_iconic_arachnida),
    IconicTaxon("Insecta", "Insects", R.drawable.ic_iconic_insecta),
    IconicTaxon("Plantae", "Plants", R.drawable.ic_iconic_plantae),
    IconicTaxon("Fungi", "Fungi", R.drawable.ic_iconic_fungi),
    IconicTaxon("Protozoa", "Protozoa", R.drawable.ic_iconic_protozoa),
)

/** Used for unidentified rows / pins (no `iconic_taxon_name` from iNat). */
internal val UnknownIconicTaxon =
    IconicTaxon("Unknown", "Unknown", R.drawable.ic_iconic_unknown)

private val byId: Map<String, IconicTaxon> =
    (FilterableIconicTaxa + UnknownIconicTaxon).associateBy { it.id }

/** Resolve an iNat `iconic_taxon_name` (or null) to the bundled icon descriptor. */
internal fun iconicTaxonForName(name: String?): IconicTaxon =
    byId[name?.takeIf(String::isNotBlank).orEmpty()] ?: UnknownIconicTaxon
