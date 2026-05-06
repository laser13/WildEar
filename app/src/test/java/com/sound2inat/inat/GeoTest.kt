package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoTest {
    @Test
    fun `haversineKm returns zero for same point`() {
        assertThat(haversineKm(50.0, 10.0, 50.0, 10.0)).isEqualTo(0f)
    }

    @Test
    fun `haversineKm Berlin to Paris is roughly 878 km`() {
        // Berlin 52.520, 13.405 → Paris 48.857, 2.352
        val km = haversineKm(52.520, 13.405, 48.857, 2.352)
        assertThat(km).isWithin(5f).of(878f)
    }

    @Test
    fun `haversineKm symmetric`() {
        val a = haversineKm(50.0, 10.0, 51.0, 11.0)
        val b = haversineKm(51.0, 11.0, 50.0, 10.0)
        assertThat(a).isEqualTo(b)
    }
}
