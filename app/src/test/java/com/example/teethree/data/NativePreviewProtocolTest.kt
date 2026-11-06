package com.example.teethree.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePreviewProtocolTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun browserUrlsStayHttpWithoutEmbeddedCredentials() {
        assertEquals("http://localhost:3000", normalizeBrowserPreviewUrl("localhost:3000"))
        assertEquals("https://example.com/path", normalizeBrowserPreviewUrl("example.com/path"))
        listOf(
                "javascript:alert(1)",
                "file:///etc/passwd",
                "https://user:secret@example.com",
                "https://",
            )
            .forEach { url ->
                assertThrows(IllegalArgumentException::class.java) {
                    normalizeBrowserPreviewUrl(url)
                }
            }
    }

    @Test
    fun browserEventsRejectOtherThreadsAndStaleRevisions() {
        val initial =
            parseBrowserPreviewState(
                json(
                    """{"serverEpoch":"a","revision":4,"sessions":[{"threadId":"t","tabId":"one","navStatus":{"_tag":"Success","url":"https://example.com","title":"First"}}]}"""
                )
            )
        assertEquals(
            initial,
            reduceBrowserPreviewEvent(
                initial,
                json(
                    """{"threadId":"t","tabId":"one","serverEpoch":"a","revision":3,"type":"closed"}"""
                ),
                "t",
            ),
        )
        assertEquals(
            initial,
            reduceBrowserPreviewEvent(
                initial,
                json(
                    """{"threadId":"another","tabId":"one","serverEpoch":"a","revision":5,"type":"closed"}"""
                ),
                "t",
            ),
        )
        val restarted =
            reduceBrowserPreviewEvent(
                initial,
                json(
                    """{"threadId":"t","tabId":"two","serverEpoch":"b","revision":1,"type":"opened","snapshot":{"threadId":"t","tabId":"two","navStatus":{"_tag":"Idle"}}}"""
                ),
                "t",
            )
        assertEquals(listOf("two"), restarted.tabs.map { it.id })
        assertEquals("b", restarted.epoch)
    }

    @Test
    fun deviceSerialsAreScopedByHostAndNewHostArraysAreMerged() {
        val state =
            parsePreviewDeviceState(
                json(
                    """{"supportsHostRetry":true,"supportsToolInspection":true,"hosts":[{"id":"local","label":"Local","tools":{"hub":{"requiredVersion":"2","installedVersions":["1"],"runningVersion":"1"},"agent":{"requiredVersion":"3","installedVersions":["3"],"runningVersion":null}},"toolInspectionError":"Host unavailable"}],"easHosts":[{"id":"cloud","label":"Cloud","kind":"eas"}],"simlockHosts":[{"id":"gateway","label":"Gateway","kind":"simlock"}],"devices":[{"hostId":"local","id":"emulator-1","name":"Local phone","platform":"android"},{"hostId":"cloud","id":"emulator-1","name":"Cloud phone","platform":"android"}]}"""
                )
            )
        assertEquals(3, state.hosts.size)
        assertEquals(2, state.devices.map { it.key }.distinct().size)
        assertEquals("simlock", state.hosts.last().kind)
        assertTrue(state.supportsHostRetry)
        assertTrue(state.supportsToolInspection)
        assertFalse(parsePreviewDeviceState(json("{}")).supportsHostRetry)
        assertFalse(parsePreviewDeviceState(json("{}")).supportsToolInspection)
        val host = state.hosts.first()
        assertEquals("Host unavailable", host.toolInspectionError)
        val versions = requireNotNull(host.tools)
        assertEquals("1", versions.obj("hub").string("runningVersion"))
        assertTrue(deviceToolsNeedUpdate(versions))
        assertFalse(
            deviceToolsNeedUpdate(
                JsonObject(
                    versions +
                        ("hub" to json("""{"requiredVersion":"2","installedVersions":["1","2"]}"""))
                )
            )
        )
        assertFalse(deviceToolsNeedUpdate(null))
        assertEquals(json("""{"retryHostId":"local"}"""), deviceHostRetryInput(" local "))
        listOf("", "host\nname", "x".repeat(129)).forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { deviceHostRetryInput(id) }
        }

        val previousServer = state.copy(revision = 40)
        val firstSnapshot = state.copy(revision = 1)
        val restarted =
            reduceDeviceSnapshot(previousServer, firstSnapshot, firstInSubscription = true)
        assertEquals(1L, restarted.revision)
        val live =
            reduceDeviceSnapshot(restarted, state.copy(revision = 4), firstInSubscription = false)
        assertEquals(4L, live.revision)
        assertEquals(
            live,
            reduceDeviceSnapshot(live, state.copy(revision = 2), firstInSubscription = false),
        )
    }

    @Test
    fun browserTransfersRejectOtherOriginsTabsAndDuplicateTargetsAndDropTickets() {
        val tab = BrowserPreviewTab("tab", "thread", "", "", false, false, "server", null)
        val good =
            "https://host.test/api/preview-stream/download?threadId=thread&tabId=tab&id=file&wsTicket=secret"
        val resolved = browserMediaLocation("https://host.test", good, tab, false)
        assertEquals(setOf("threadId", "tabId", "id"), resolved.second.keys)
        assertFalse(resolved.second.values.contains("secret"))
        listOf(
                good.replace("host.test", "evil.test"),
                good.replace("tabId=tab", "tabId=another"),
                good + "&id=second",
                good.replace("/download", "/../auth/access"),
            )
            .forEach { url ->
                assertThrows(IllegalArgumentException::class.java) {
                    browserMediaLocation("https://host.test", url, tab, false)
                }
            }
    }

    @Test
    fun mcpReferencesPreserveSourceAndOnlyAcceptScopedCspOrigins() {
        val raw =
            json(
                """{"attachmentId":"a","server":"maps","tool":"show","resourceUri":"ui://maps/app","csp":{"connectDomains":["https://api.example.com","*","https://user:secret@evil.test","data:","https://example.com/path"]}}"""
            )
        val app = parseNativeMcpApp(raw, "source", "item")
        assertNotNull(app)
        assertEquals("source", app!!.sourceThreadId)
        assertEquals(listOf("https://api.example.com"), app.csp["connectDomains"])
        assertNull(
            parseNativeMcpApp(
                JsonObject(raw + ("resourceUri" to JsonPrimitive("https://example.com"))),
                "source",
                "item",
            )
        )
        val policy = mcpContentPolicy(app)
        assertTrue(policy.contains("default-src 'none'"))
        assertTrue(policy.contains("frame-src 'none'"))
        assertFalse(policy.contains("'self'"))
    }

    @Test
    fun mcpDomainMatchingDoesNotWidenSchemesPortsOrWildcards() {
        assertTrue(mcpDomainAllows("https://*.example.com", "https://a.example.com/file"))
        assertFalse(mcpDomainAllows("https://*.example.com", "https://example.com/file"))
        assertFalse(
            mcpDomainAllows("https://*.example.com", "https://a.example.com.evil.test/file")
        )
        assertFalse(mcpDomainAllows("https://example.com", "http://example.com/file"))
        assertFalse(mcpDomainAllows("https://example.com", "https://example.com:444/file"))
        assertFalse(mcpDomainAllows("https://example.com", "https://user:secret@example.com/file"))
    }

    @Test
    fun mcpMessagesAcceptOnlyExplicitUserTextAndNormalizeNullableSdkMetadata() {
        assertNull(mcpRequestId(JsonPrimitive(true)))
        assertNull(mcpRequestId(kotlinx.serialization.json.JsonNull))
        assertEquals(JsonPrimitive("request"), mcpRequestId(JsonPrimitive("request")))
        assertEquals(
            "Hello\nWorld",
            mcpUserMessage(
                json(
                    """{"role":"user","content":[{"type":"text","text":"Hello"},{"type":"text","text":"World"}]}"""
                )
            ),
        )
        listOf(
                """{"role":"assistant","content":{"type":"text","text":"x"}}""",
                """{"role":"user","content":{"type":"image","text":"x"}}""",
            )
            .forEach { raw ->
                assertThrows(IllegalArgumentException::class.java) { mcpUserMessage(json(raw)) }
            }
        assertEquals(
            json("""{"content":[]}"""),
            normalizeMcpToolResult(
                json("""{"content":[],"_meta":null,"structuredContent":null,"isError":false}""")
            ),
        )
        val timeline =
            parseTimelineItem(
                json(
                    """{"id":"item","threadId":"source","type":"dynamic_tool","output":{"t3McpApp":{"attachmentId":"a","server":"maps","tool":"show","resourceUri":"ui://maps/app"}}}"""
                ),
                "source:item",
            )
        assertEquals("source", timeline.mcpApp?.sourceThreadId)
        assertEquals("item", timeline.mcpApp?.itemId)
    }
}
