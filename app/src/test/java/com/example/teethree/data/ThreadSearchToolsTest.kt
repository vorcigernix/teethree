package com.example.teethree.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadSearchToolsTest {
    @Test
    fun progressiveResultKeepsActualOccurrenceBeforeTheCompleteCountArrives() {
        val partial =
            parseThreadFindResult(
                json(
                    """{"complete":false,"snapshotSequence":4,"totalMatches":1,"activeIndex":0,"match":{"entryId":"message-old","runId":"run","occurrence":7}}"""
                )
            )
        assertFalse(partial.complete)
        assertEquals(7, partial.match!!.occurrence)
        val complete =
            parseThreadFindResult(
                json(
                    """{"snapshotSequence":4,"totalMatches":15,"activeIndex":9,"match":{"entryId":"message-old","runId":"run","occurrence":7},"navigation":[{"entryId":"previous","runId":null,"startIndex":0,"count":2},{"entryId":"message-old","runId":"run","startIndex":2,"count":10},{"entryId":"next","runId":null,"startIndex":12,"count":3}]}"""
                )
            )
        assertTrue(complete.complete)
        assertEquals(partial.match, complete.match)
        assertEquals(15, complete.totalMatches)
        assertEquals(9, complete.activeIndex)
        val empty =
            parseThreadFindResult(
                json("""{"snapshotSequence":4,"totalMatches":0,"activeIndex":0,"match":null}""")
            )
        assertNull(empty.match)
        assertTrue(empty.complete)
    }

    @Test
    fun malformedSelectionsAndOverlappingOrOverflowingNavigationAreRejected() {
        for (body in
            listOf(
                """{"snapshotSequence":0,"totalMatches":0,"activeIndex":0,"match":{"entryId":"x","occurrence":0}}""",
                """{"snapshotSequence":0,"totalMatches":1,"activeIndex":1,"match":{"entryId":"x","occurrence":0}}""",
                """{"snapshotSequence":0,"totalMatches":1,"activeIndex":0,"match":{"entryId":"x","occurrence":-1}}""",
                """{"snapshotSequence":0,"totalMatches":3,"activeIndex":0,"match":{"entryId":"x","occurrence":0},"navigation":[{"entryId":"x","startIndex":0,"count":2},{"entryId":"y","startIndex":1,"count":2}]}""",
                """{"snapshotSequence":0,"totalMatches":3,"activeIndex":0,"match":{"entryId":"x","occurrence":0},"navigation":[{"entryId":"x","startIndex":2,"count":2147483647}]}""",
            )) assertThrows(IllegalArgumentException::class.java) {
            parseThreadFindResult(json(body))
        }
    }

    @Test
    fun requestUsesStableEntryAndOccurrenceWithoutConfusingRunOrSourceItemIds() {
        val request =
            threadFindPayload(
                "thread",
                "  needle  ",
                start = ThreadFindMatch("message", "run", 3),
                offset = -1,
            )
        assertEquals("needle", request.string("query"))
        assertEquals("message", request.obj("start").string("entryId"))
        assertEquals(3L, request.obj("start").long("occurrence"))
        assertEquals(-1L, request.long("offset"))
        assertFalse(request.containsKey("runId"))
        assertThrows(IllegalArgumentException::class.java) { threadFindPayload("thread", " ") }
        assertThrows(IllegalArgumentException::class.java) {
            threadFindPayload("thread", "x".repeat(201))
        }
    }

    @Test
    fun occurrenceOffsetsMatchServerCaseFoldingWithoutChangingUtf16Positions() {
        assertEquals(listOf(0, 6), threadSearchOffsets("can’t CAN'T", "CAN'T".take(3)))
        assertEquals(listOf(0, 6), threadSearchOffsets("can’t CAN'T", "can't"))
        assertEquals(listOf(0, 2), threadSearchOffsets("ς Σ", "σ"))
        assertEquals(listOf(2, 5), threadSearchOffsets("😀Aa aa", "aa"))
        assertEquals(emptyList<Int>(), threadSearchOffsets("İ", "i"))
        assertEquals(listOf(0, 2), threadSearchOffsets("aaaaa", "aa"))
    }

    private fun json(source: String) = Json.parseToJsonElement(source).jsonObject
}
