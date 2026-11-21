package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayJournalTest {

    private fun queueOf(vararg keys: String) = keys.toList()

    @Test
    fun record_skipsBlankAndConsecutiveDuplicates() {
        val journal = PlayJournal()
        journal.record("", true)
        journal.record("a", true)
        journal.record("a", false)
        assertThat(journal.size).isEqualTo(1)
        assertThat(journal.keys()).containsExactly("a").inOrder()
    }

    @Test
    fun peekPrevious_returnsNewestPlayedTrackStillInQueue() {
        val journal = PlayJournal()
        journal.record("a", false)
        journal.record("b", true)
        journal.record("c", false)

        val prev = journal.peekPrevious("c", queueOf("a", "b", "c"))
        assertThat(prev?.key).isEqualTo("b")
        assertThat(prev?.wasUserQueued).isTrue()
        // Peek is non-destructive.
        assertThat(journal.size).isEqualTo(3)
    }

    @Test
    fun peekPrevious_skipsEntriesThatLeftTheQueue() {
        val journal = PlayJournal()
        journal.record("gone", true)
        journal.record("kept", false)
        journal.record("current", false)

        val prev = journal.peekPrevious("current", queueOf("kept", "current"))
        assertThat(prev?.key).isEqualTo("kept")
    }

    @Test
    fun peekPrevious_nullWhenOnlyCurrentIsKnown() {
        val journal = PlayJournal()
        journal.record("current", false)
        assertThat(journal.peekPrevious("current", queueOf("current"))).isNull()
    }

    @Test
    fun popPrevious_consumesThroughTheFoundEntry() {
        val journal = PlayJournal()
        journal.record("a", false)
        journal.record("b", true)
        journal.record("c", false)

        val popped = journal.popPrevious("c") { true }
        assertThat(popped?.key).isEqualTo("b")
        assertThat(journal.keys()).containsExactly("a").inOrder()

        val next = journal.popPrevious("c") { true }
        assertThat(next?.key).isEqualTo("a")
        assertThat(journal.size).isEqualTo(0)
    }

    @Test
    fun popPrevious_skipsEntriesNotPresent() {
        val journal = PlayJournal()
        journal.record("a", false)
        journal.record("missing", true)
        journal.record("c", false)

        val popped = journal.popPrevious("c") { it != "missing" }
        assertThat(popped?.key).isEqualTo("a")
    }

    @Test
    fun consumeThrough_leavesEarlierEntriesIntact() {
        val journal = PlayJournal()
        journal.record("a", false)
        journal.record("b", true)
        journal.record("c", false)
        journal.record("d", false)

        val entry = PlayJournal.Entry("c", false)
        journal.consumeThrough(entry)
        assertThat(journal.keys()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun capacity_dropsOldestEntries() {
        val journal = PlayJournal(capacity = 3)
        repeat(6) { journal.record("k$it", false) }
        assertThat(journal.keys()).containsExactly("k3", "k4", "k5").inOrder()
    }

    @Test
    fun clear_emptiesJournal() {
        val journal = PlayJournal()
        journal.record("a", true)
        journal.clear()
        assertThat(journal.size).isEqualTo(0)
    }
}
