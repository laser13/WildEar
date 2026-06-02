package com.sound2inat.app.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar

class DateGroupingTest {
    private fun atLocalDay(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month, day, hour, 0, 0)
        return cal.timeInMillis
    }

    @Test
    fun `empty input yields no groups`() {
        val result = groupDatedItems(emptyList<Long>(), now = atLocalDay(2026, 4, 30)) { it }
        assertThat(result).isEmpty()
    }

    @Test
    fun `recent days get concrete date labels`() {
        val now = atLocalDay(2026, 4, 30)
        val today = atLocalDay(2026, 4, 30, hour = 9)
        val yesterday = atLocalDay(2026, 4, 29, hour = 9)
        val items = listOf(today, yesterday)

        val groups = groupDatedItems(items, now = now) { it }

        assertThat(groups.map { it.label }).containsExactly("30 May 2026", "29 May 2026").inOrder()
        assertThat(groups[0].items).containsExactly(today)
        assertThat(groups[1].items).containsExactly(yesterday)
    }

    @Test
    fun `items on the same day collapse into one group`() {
        val now = atLocalDay(2026, 4, 30)
        val morning = atLocalDay(2026, 4, 30, hour = 8)
        val evening = atLocalDay(2026, 4, 30, hour = 20)

        val groups = groupDatedItems(listOf(evening, morning), now = now) { it }

        assertThat(groups).hasSize(1)
        assertThat(groups[0].label).isEqualTo("30 May 2026")
        assertThat(groups[0].items).containsExactly(evening, morning).inOrder()
    }

    @Test
    fun `older items also get a concrete date label`() {
        val now = atLocalDay(2026, 4, 30)
        val old = atLocalDay(2026, 0, 15) // Jan 15, 2026

        val groups = groupDatedItems(listOf(old), now = now) { it }

        assertThat(groups).hasSize(1)
        assertThat(groups[0].label).isEqualTo("15 January 2026")
    }
}
