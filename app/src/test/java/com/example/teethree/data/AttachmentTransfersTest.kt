package com.example.teethree.data

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class AttachmentTransfersTest {
    @Test
    fun signedUploadUrlsStayBoundToTheEnvironmentAndUploadRoute() {
        assertEquals(
            "https://environment.test/api/attachments/upload/signed?token=secret",
            resolveSignedAssetUrl(
                "https://environment.test",
                "/api/attachments/upload/signed?token=secret",
            ),
        )
        assertEquals(
            "http://localhost:3773/api/attachments/upload/id",
            resolveSignedAssetUrl("http://localhost:3773", "/api/attachments/upload/id"),
        )
        listOf(
                "https://other.test/api/attachments/upload/id",
                "//other.test/api/attachments/upload/id",
                "http://environment.test/api/attachments/upload/id",
                "https://user:pass@environment.test/api/attachments/upload/id",
                "/api/attachments/upload/id#fragment",
                "/oauth/token",
            )
            .forEach { url ->
                assertFailsWith<IllegalArgumentException> {
                    resolveSignedAssetUrl("https://environment.test", url)
                }
            }
    }
}
