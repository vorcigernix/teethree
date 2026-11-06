package com.example.teethree.ui

import com.example.teethree.data.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStatusTest {
    private val start = 1_000_000L

    private fun phase(
        status: ConnectionStatus = ConnectionStatus.Reconnecting,
        downFor: Long,
        syncing: Boolean = false,
    ) = linkPhase(status, start, start + downFor, syncing)

    @Test
    fun connectingStaysQuietForThirtySeconds() {
        assertEquals(LinkPhase.Connecting, phase(ConnectionStatus.Connecting, downFor = 0))
        assertEquals(LinkPhase.Connecting, phase(downFor = 29_999))
        assertEquals(LinkPhase.Unreachable, phase(downFor = 30_000))
        // Nothing will retry on its own.
        assertEquals(LinkPhase.Unreachable, phase(ConnectionStatus.Disconnected, downFor = 0))
    }

    @Test
    fun connectedSyncsThenGoesQuiet() {
        assertEquals(LinkPhase.Syncing, phase(ConnectionStatus.Connected, downFor = 0, syncing = true))
        assertEquals(LinkPhase.Online, phase(ConnectionStatus.Connected, downFor = 0))
    }

    @Test
    fun statusLineNamesTheHost() {
        assertEquals("Connecting to devbox…", statusLineText(LinkPhase.Connecting, "devbox"))
        assertEquals("Can't reach devbox", statusLineText(LinkPhase.Unreachable, "devbox"))
        assertNull(statusLineText(LinkPhase.Online, "devbox"))
    }

    @Test
    fun elapsedTimesStayShort() {
        assertEquals("42 s", elapsedShort(42_000))
        assertEquals("1 m 20 s", elapsedShort(80_000))
        assertEquals("12 m", elapsedShort(12 * 60_000 + 5_000))
        assertEquals("3 h", elapsedShort(3 * 3_600_000L))
    }
}
