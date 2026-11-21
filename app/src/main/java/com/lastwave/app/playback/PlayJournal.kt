package com.lastwave.app.playback

/**
 * Listening journal behind smart Previous: every track that actually plays
 * appends an entry recording whether it was a user queue insertion
 * (play-next / add-to-queue) or part of the original context. Previous
 * consults the journal so a played queue insertion can be offered back
 * separately from the playlist position it displaced.
 *
 * Keys are [PlayableTrack.mediaIdKey]s; entries whose track later left the
 * queue can't be jumped to and are skipped by [peekPrevious].
 */
internal class PlayJournal(private val capacity: Int = 1_000) {
    data class Entry(val key: String, val wasUserQueued: Boolean)

    private val entries = ArrayDeque<Entry>()

    val size: Int get() = entries.size

    fun record(key: String, wasUserQueued: Boolean) {
        if (key.isBlank()) return
        if (entries.lastOrNull()?.key == key) return
        entries.addLast(Entry(key, wasUserQueued))
        while (entries.size > capacity) entries.removeFirst()
    }

    fun clear() = entries.clear()

    fun keys(): List<String> = entries.map { it.key }

    /**
     * Newest entry that is not [currentKey] and still exists in the queue,
     * without consuming anything. Returns null when every earlier played
     * track has left the queue (or nothing has played yet).
     */
    fun peekPrevious(currentKey: String?, queueKeys: Collection<String>): Entry? {
        val present = if (queueKeys is Set<String>) queueKeys else queueKeys.toSet()
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            if (entry.key == currentKey) continue
            if (entry.key in present) return entry
        }
        return null
    }

    /**
     * Consume journal entries newest-first down to and including [entry],
     * so replaying it and pressing Previous again steps one song further
     * back — the same destructive walk the shuffle history had.
     */
    fun consumeThrough(entry: Entry) {
        while (entries.isNotEmpty()) {
            val removed = entries.removeLast()
            if (removed == entry) break
        }
    }

    /**
     * Destructive walk for classic shuffle-previous: consume entries
     * newest-first, skipping [currentKey], until one still present in the
     * queue (per [isPresent]) is found. Entries that left the queue are
     * consumed and skipped, exactly like the old ArrayDeque history.
     */
    fun popPrevious(currentKey: String?, isPresent: (String) -> Boolean): Entry? {
        while (entries.isNotEmpty()) {
            val entry = entries.removeLast()
            if (entry.key == currentKey) continue
            if (isPresent(entry.key)) return entry
        }
        return null
    }
}
