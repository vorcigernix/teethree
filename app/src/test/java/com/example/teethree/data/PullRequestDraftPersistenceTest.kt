package com.example.teethree.data

import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class PullRequestDraftPersistenceTest {
    @Test
    fun `latest pending draft survives reopen and closing drains it while account changes discard it`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val key =
                reviewDraftStorageKey("relay:account:environment", "project/host/repository/1")
            assertNotEquals(
                key,
                reviewDraftStorageKey("relay:another:environment", "project/host/repository/1"),
            )
            var stored: String? = null
            var current = true
            val writes = mutableListOf<String>()
            fun writer() =
                PullRequestDraftPersistence(
                    key,
                    { stored },
                    { value, allowed ->
                        if (allowed()) {
                            stored = value
                            writes += value
                            true
                        } else false
                    },
                    { current },
                    { fail(it) },
                    dispatcher,
                )
            val first = writer()
            first.save(buildJsonObject { put("summary", "first") })
            val latest = buildJsonObject {
                put("summary", "latest")
                put("comments", JsonArray(emptyList()))
            }
            first.save(latest)
            val reopened = writer()
            assertEquals(latest, reopened.load())
            first.close()
            first.awaitClosed()
            assertEquals(latest, Json.parseToJsonElement(stored!!))
            assertEquals(1, writes.size)
            reopened.save(buildJsonObject { put("summary", "must not return after sign out") })
            current = false
            stored = null
            reopened.close()
            reopened.awaitClosed()
            assertNull(stored)
            assertEquals(1, writes.size)
            Unit
        }
}
