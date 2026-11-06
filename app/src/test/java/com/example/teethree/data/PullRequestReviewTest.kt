package com.example.teethree.data

import kotlin.test.*
import kotlinx.serialization.json.*

class PullRequestReviewTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test
    fun `line comments preserve both sides and rename paths across hunks`() {
        val files =
            parseReviewDiff(
                """diff --git a/old.kt b/new.kt
--- a/old.kt
+++ b/new.kt
@@ -3,2 +3,3 @@
 unchanged
-removed
+added
+extra
@@ -9 +10 @@
-last
+next
"""
            )
        val file = files.single()
        val addition = file.lines.first { it.text == "+added" }
        assertEquals(4, addition.newLine)
        assertNull(addition.oldLine)
        val deletion = file.lines.first { it.text == "-last" }
        assertEquals(9, deletion.oldLine)
        val draft =
            inlineReviewDraft(
                file,
                addition,
                "Keep this",
                "right",
                file.lines.first { it.kind == "context" },
            )
        assertEquals("old.kt", draft.string("oldPath"))
        assertEquals("new.kt", draft.string("path"))
        assertEquals("added", draft.obj("position").string("kind"))
        assertEquals(3L, draft.obj("startPosition").long("newLine"))
        assertFailsWith<IllegalArgumentException> { inlineReviewDraft(file, addition, "", "right") }
        assertFailsWith<IllegalArgumentException> {
            inlineReviewDraft(file, addition, "x", "left", deletion)
        }
        assertFailsWith<IllegalArgumentException> {
            file.lines.first { it.kind == null }.position()
        }
        val quoted =
            parseReviewDiff(
                    """diff --git "a/caf\303\251\told.kt" "b/caf\303\251 new.kt"
--- "a/caf\303\251\told.kt"
+++ "b/caf\303\251 new.kt"
@@ -1 +1 @@
-before
+after
"""
                )
                .single()
        assertEquals("café new.kt", quoted.path)
        assertEquals("café\told.kt", quoted.oldPath)
        assertEquals(
            "café new.kt",
            inlineReviewDraft(quoted, quoted.lines.last { it.kind == "added" }, "Check")
                .string("path"),
        )
        val binary =
            parseReviewDiff(
                    "diff --git a/icon.png b/icon.png\nBinary files a/icon.png and b/icon.png differ"
                )
                .single()
        assertEquals("icon.png", binary.path)
        val malformed =
            parseReviewDiff(
                    "diff --git a/a b/a\n--- a/a\n+++ \"b/bad\\400.kt\"\n@@ -1 +1 @@\n-before\n+after"
                )
                .single()
        assertFalse(malformed.commentable)
        assertFailsWith<IllegalArgumentException> {
            inlineReviewDraft(malformed, malformed.lines.last { it.kind == "added" }, "Check")
        }
        val quote =
            parseReviewDiff(
                    "diff --git \"a/\\\"hello.kt\" \"b/\\\"hello.kt\"\n--- \"a/\\\"hello.kt\"\n+++ \"b/\\\"hello.kt\"\n@@ -1 +1 @@\n-a\n+b"
                )
                .single()
        assertEquals(
            "\"hello.kt",
            inlineReviewDraft(quote, quote.lines.last { it.kind == "added" }, "Check")
                .string("path"),
        )
    }

    @Test
    fun `follow ups steer only an identified active run`() {
        assertEquals("start_immediately", messageDispatchMode(null, "steer").string("type"))
        assertEquals("queue_after_active", messageDispatchMode("run", "queue").string("type"))
        val steering = messageDispatchMode("run", "steer")
        assertEquals("steer_active", steering.string("type"))
        assertEquals("run", steering.string("targetRunId"))
        assertFailsWith<IllegalArgumentException> { messageDispatchMode("run", "unknown") }
    }

    @Test
    fun `config deltas preserve catalog and usage sources independently`() {
        val initial =
            json(
                """{"providers":[{"instanceId":"codex"}],"settings":{"title":"Host"},"usageLimitSources":[{"sourceId":"a"}]}"""
            )
        val usage =
            mergeServerConfig(
                initial,
                json(
                    """{"type":"usageLimitSourcesUpdated","payload":{"sources":[{"sourceId":"b"}]}}"""
                ),
            )!!
        assertEquals("codex", usage.array("providers").single().jsonObject.string("instanceId"))
        assertEquals("b", usage.array("usageLimitSources").single().jsonObject.string("sourceId"))
        val providers =
            mergeServerConfig(
                usage,
                json("""{"type":"providerStatuses","payload":{"providers":[]}}"""),
            )!!
        assertTrue(providers.array("providers").isEmpty())
        assertEquals("Host", providers.obj("settings").string("title"))
        assertEquals(usage["usageLimitSources"], providers["usageLimitSources"])
    }

    @Test
    fun `mutations require capabilities and current viewer permission and merge settings`() {
        val detail =
            json(
                """{"state":"open","isDraft":false,
          "capabilities":{"actions":["merge","close","update-branch"],"mergeMethods":["merge","squash"],"updateMethods":["merge","rebase"],"labels":true,"edit":{"changeRequest":true},"review":{"resolve":true,"reply":true,"inlineComment":true,"rangeComment":true,"verdicts":["approve"]}},
          "viewerPermissions":{"actions":["merge","update-branch"],"updateMethods":["rebase"],"resolve":false,"comment":false,"labels":false,"editChangeRequest":false,"verdicts":["approve"]},
          "mergeCapabilities":{"merge":false,"squash":true}}
        """
            )
        val p = pullRequestPermissions(detail)
        assertEquals(setOf("merge", "update-branch"), p.actions)
        assertEquals(setOf("squash"), p.mergeMethods)
        assertEquals(setOf("rebase"), p.updateMethods)
        assertFalse(p.labels)
        assertFalse(p.edit)
        assertFalse(p.resolve)
        assertFalse(p.reply)
        assertFalse(p.inlineComment)
        assertFalse(p.rangeComment)
        assertEquals(
            "squash",
            pullRequestActionInput(detail, "merge", "squash", null, null).string("mergeMethod"),
        )
        assertFailsWith<IllegalArgumentException> {
            pullRequestActionInput(detail, "close", null, null, null)
        }
        assertFailsWith<IllegalArgumentException> {
            pullRequestActionInput(detail, "merge", "merge", null, null)
        }
        assertFailsWith<IllegalArgumentException> {
            pullRequestActionInput(detail, "update-branch", null, "merge", null)
        }
        val stale = JsonObject(detail + ("isDraft" to JsonPrimitive(true)))
        assertFailsWith<IllegalArgumentException> {
            pullRequestActionInput(stale, "merge", "squash", null, null)
        }
        assertEquals(
            setOf("source-control:write"),
            requiredToolScopes("pullRequests.runAction", JsonObject(emptyMap())),
        )
        assertEquals(
            setOf("orchestration:read"),
            requiredToolScopes("pullRequests.threadComments", JsonObject(emptyMap())),
        )
        val stacked =
            JsonObject(
                detail +
                    mapOf(
                        "number" to JsonPrimitive(2),
                        "capabilities" to
                            JsonObject(
                                detail.obj("capabilities") + ("stackActions" to JsonPrimitive(true))
                            ),
                    )
            )
        val stack =
            json(
                """{"number":77,"layers":[{"number":1,"state":"merged"},{"number":2,"state":"open","headSha":"second"},{"number":3,"state":"open","headSha":"third"}]}"""
            )
        val merging = pullRequestActionInput(stacked, "merge", "squash", null, stack)
        assertEquals(
            listOf(2L),
            merging.array("expectedStackHeads").map { it.jsonObject.long("number") },
        )
        val closed =
            json(
                """{"number":77,"layers":[{"number":1,"state":"closed","headSha":"first"},{"number":2,"state":"open","headSha":"second"}]}"""
            )
        assertFailsWith<IllegalArgumentException> {
            pullRequestActionInput(stacked, "merge", "squash", null, closed)
        }
        assertFailsWith<IllegalArgumentException> {
            pullRequestActionInput(stacked, "close", null, null, stack)
        }
        val rebase =
            JsonObject(
                stacked +
                    ("viewerPermissions" to
                        JsonObject(
                            stacked.obj("viewerPermissions") +
                                mapOf(
                                    "stackRebase" to JsonPrimitive(true),
                                    "actions" to JsonArray(emptyList()),
                                    "updateMethods" to JsonArray(emptyList()),
                                )
                        ))
            )
        assertTrue(pullRequestPermissions(rebase).stackRebase)
        assertFailsWith<IllegalArgumentException> {
            pullRequestActionInput(rebase, "update-branch", null, "rebase", stack)
        }
        val top = JsonObject(rebase + ("number" to JsonPrimitive(3)))
        assertEquals(
            listOf(2L, 3L),
            pullRequestActionInput(top, "update-branch", null, "rebase", stack)
                .array("expectedStackHeads")
                .map { it.jsonObject.long("number") },
        )
        assertFailsWith<IllegalArgumentException> {
            pullRequestActionInput(top, "update-branch", null, "merge", stack)
        }
        val owner =
            json(
                """{"viewer":"Alice","author":{"login":"alice"},"capabilities":{"edit":{"changeRequest":true,"comment":true}}}"""
            )
        assertTrue(pullRequestPermissions(owner).edit)
        assertTrue(
            canEditReviewComment(
                owner,
                json("""{"kind":"issue-comment","author":{"login":"alice"}}"""),
            )
        )
        assertFalse(
            canEditReviewComment(
                owner,
                json("""{"kind":"review","canEdit":true,"author":{"login":"alice"}}"""),
            )
        )
        assertFalse(
            canEditReviewComment(
                owner,
                json("""{"kind":"issue-comment","canEdit":false,"author":{"login":"alice"}}"""),
            )
        )
    }

    @Test
    fun `review comments stay bound to their head and only wire fields are submitted`() {
        val detail =
            json(
                """{"provider":"github","state":"open","headSha":"current","capabilities":{"review":{"inlineComment":true,"rangeComment":true,"verdicts":["approve","comment","request-changes"]}},"viewerPermissions":{"comment":true,"verdicts":["approve","comment","request-changes"]}}"""
            )
        val draft =
            pendingReviewDraft(
                json(
                    """{"path":"src/file.kt","position":{"kind":"added","newLine":1},"body":"Review this","id":"local","code":"+line"}"""
                ),
                "current",
            )
        val input = pullRequestReviewInput(detail, "comment", "", listOf(draft))
        assertEquals(
            setOf("path", "position", "body"),
            input.array("comments").single().jsonObject.keys,
        )
        assertFailsWith<IllegalArgumentException> { pendingReviewDraft(draft, null) }
        assertFailsWith<IllegalArgumentException> {
            pullRequestReviewInput(
                detail,
                "comment",
                "",
                listOf(JsonObject(draft + ("headSha" to JsonPrimitive("older")))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            pullRequestReviewInput(detail, "comment", "", listOf(JsonObject(draft - "headSha")))
        }
        assertFailsWith<IllegalArgumentException> {
            pullRequestReviewInput(detail, "comment", "", emptyList())
        }
        assertEquals(
            "approve",
            pullRequestReviewInput(detail, "approve", "", emptyList()).string("verdict"),
        )
        val forgejo = JsonObject(detail + ("provider" to JsonPrimitive("forgejo")))
        assertFailsWith<IllegalArgumentException> {
            pullRequestReviewInput(forgejo, "request-changes", "", listOf(draft))
        }
        assertEquals(
            "request-changes",
            pullRequestReviewInput(forgejo, "request-changes", "A summary", listOf(draft))
                .string("verdict"),
        )
        val readOnly =
            JsonObject(
                detail +
                    ("viewerPermissions" to json("""{"comment":false,"verdicts":["approve"]}"""))
            )
        assertFailsWith<IllegalArgumentException> {
            pullRequestReviewInput(readOnly, "approve", "", listOf(draft))
        }
    }

    @Test
    fun `test file filters include fixtures but keep generated changes visible`() {
        assertEquals(
            listOf(true, true, true, true, false, false, false, false),
            listOf(
                    "src/UserTest.kt",
                    "tests/case.ts",
                    "src/__mocks__/api.ts",
                    "src/fixtures.json",
                    "src/foo.test.generated.ts",
                    "tests/__generated__/schema.ts",
                    "app/src/Main.kt",
                    "README.md",
                )
                .map(::isReviewTestFile),
        )
    }
}
