package com.example.teethree

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkAccessTest {
    @Test
    fun localConnectionsIncludeEmulatorTailscaleAndIpv6Networks() {
        listOf(
                "127.0.0.1",
                "10.0.2.2",
                "192.168.1.5",
                "172.16.0.1",
                "169.254.1.1",
                "100.64.0.1",
                "100.127.255.254",
                "::1",
                "fd12::1",
                "fc00::1",
                "fe80::1",
            )
            .forEach { literal ->
                assertTrue(
                    literal,
                    requiresLocalNetworkAccess(
                        "environment.example",
                        listOf(InetAddress.getByName(literal)),
                    ),
                )
            }
        listOf("8.8.8.8", "100.63.255.254", "100.128.0.1", "2606:4700:4700::1111").forEach { literal
            ->
            assertFalse(
                literal,
                requiresLocalNetworkAccess(
                    "environment.example",
                    listOf(InetAddress.getByName(literal)),
                ),
            )
        }
        listOf("localhost", "dev.localhost", "MacBook.local.", "environment.tailnet.ts.net")
            .forEach { host ->
                assertTrue(host, requiresLocalNetworkAccess(host, emptyList()))
            }
        assertFalse(requiresLocalNetworkAccess("environment.example", emptyList()))
    }
}
