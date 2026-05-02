package com.sound2inat.app.ui.radar

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IconicTaxaTest {
    @Test fun `FilterableIconicTaxa contains 11 taxa with stable order`() {
        assertThat(FilterableIconicTaxa).hasSize(11)
        assertThat(FilterableIconicTaxa.first().id).isEqualTo("Aves")
        assertThat(FilterableIconicTaxa.last().id).isEqualTo("Protozoa")
        assertThat(FilterableIconicTaxa.none { it.id == "Unknown" }).isTrue()
    }

    @Test fun `iconicTaxonForName returns FilterableIconicTaxa entry by id`() {
        val aves = iconicTaxonForName("Aves")
        assertThat(aves.id).isEqualTo("Aves")
        assertThat(aves.label).isEqualTo("Birds")
    }

    @Test fun `iconicTaxonForName falls back to Unknown for null or blank`() {
        assertThat(iconicTaxonForName(null).id).isEqualTo("Unknown")
        assertThat(iconicTaxonForName("").id).isEqualTo("Unknown")
        assertThat(iconicTaxonForName("Animalia").id).isEqualTo("Unknown")
    }
}
