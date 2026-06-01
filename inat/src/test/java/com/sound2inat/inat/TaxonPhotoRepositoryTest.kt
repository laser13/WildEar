package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TaxonPhotoRepositoryTest {

    private class FakeClient(
        private val urlFor: (String) -> String? = { "https://example.com/$it.jpg" },
    ) {
        val calls = AtomicInteger(0)

        suspend fun fetchTaxonPhotoUrl(name: String): String? {
            calls.incrementAndGet()
            return urlFor(name)
        }
    }

    private fun repo(fake: FakeClient) = TaxonPhotoRepository(
        fetchUrl = fake::fetchTaxonPhotoUrl,
    )

    // ─── cache hit ────────────────────────────────────────────────────────────

    @Test fun `same taxon observed twice makes exactly one network call`() = runTest {
        val fake = FakeClient()
        val r = repo(fake)

        r.observe("Parus major").toList()
        r.observe("Parus major").toList()

        assertThat(fake.calls.get()).isEqualTo(1)
    }

    @Test fun `cached url is returned on second observe`() = runTest {
        val fake = FakeClient { "https://example.com/photo.jpg" }
        val r = repo(fake)

        val first = r.observe("Parus major").toList().last()
        val second = r.observe("Parus major").toList().last()

        assertThat(first).isEqualTo("https://example.com/photo.jpg")
        assertThat(second).isEqualTo("https://example.com/photo.jpg")
    }

    // ─── null / error handling ────────────────────────────────────────────────

    @Test fun `null photo url is cached and emitted without crash`() = runTest {
        val fake = FakeClient { null }
        val r = repo(fake)

        val emissions = r.observe("Unknown bird").toList()

        assertThat(emissions.last()).isNull()
        assertThat(fake.calls.get()).isEqualTo(1)

        // Second observe: still null, still 1 call
        r.observe("Unknown bird").toList()
        assertThat(fake.calls.get()).isEqualTo(1)
    }

    @Test fun `network exception results in null emission without crash`() = runTest {
        var threw = false
        val r = TaxonPhotoRepository(fetchUrl = { _ ->
            threw = true
            throw RuntimeException("Network failure")
        })

        val emissions = r.observe("Parus major").toList()

        assertThat(threw).isTrue()
        assertThat(emissions.last()).isNull()
    }

    // ─── distinct taxa ─────────────────────────────────────────────────────────

    @Test fun `different taxon names produce independent results`() = runTest {
        val fake = FakeClient { name -> "https://example.com/$name.jpg" }
        val r = repo(fake)

        val url1 = r.observe("Parus major").toList().last()
        val url2 = r.observe("Corvus cornix").toList().last()

        assertThat(url1).isEqualTo("https://example.com/Parus major.jpg")
        assertThat(url2).isEqualTo("https://example.com/Corvus cornix.jpg")
        assertThat(fake.calls.get()).isEqualTo(2)
    }

    // ─── LRU eviction ────────────────────────────────────────────────────────

    @Test fun `LRU evicts beyond maxEntries so evicted taxon is re-fetched`() = runTest {
        val fake = FakeClient()
        val r = repo(fake)

        val firstTaxon = "Taxon_0"
        r.observe(firstTaxon).toList()
        assertThat(fake.calls.get()).isEqualTo(1)

        // Fill the LRU past capacity — this evicts firstTaxon (LRU order)
        for (i in 1..TaxonPhotoRepository.MAX_ENTRIES) {
            r.observe("Taxon_$i").toList()
        }
        val callsAfterFill = fake.calls.get()
        assertThat(callsAfterFill).isEqualTo(TaxonPhotoRepository.MAX_ENTRIES + 1)

        // firstTaxon has been evicted — re-observing it must trigger a new network call
        r.observe(firstTaxon).toList()
        assertThat(fake.calls.get()).isEqualTo(callsAfterFill + 1)
    }

    // ─── emission shape ────────────────────────────────────────────────────────

    @Test fun `observe emits null first then the url on cache miss`() = runTest {
        val fake = FakeClient()
        val r = repo(fake)

        val emissions = r.observe("Parus major").toList()

        assertThat(emissions).hasSize(2)
        assertThat(emissions[0]).isNull()
        assertThat(emissions[1]).isNotNull()
    }

    @Test fun `observe emits only one value when taxon is already cached`() = runTest {
        val fake = FakeClient()
        val r = repo(fake)

        r.observe("Parus major").toList()
        val emissions = r.observe("Parus major").toList()

        assertThat(emissions).hasSize(1)
    }
}
