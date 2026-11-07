package com.example.teethree

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkGuessTest {
    @Test
    fun privateAndLocalAddressesAskForLocalNetworkAccess() {
        assertTrue(looksLikeLocalNetwork("http://10.0.2.2:3773"))
        assertTrue(looksLikeLocalNetwork("192.168.1.24:13773"))
        assertTrue(looksLikeLocalNetwork("http://devbox.local:3773"))
        assertTrue(looksLikeLocalNetwork("https://devbox.tail1234.ts.net"))
    }

    @Test
    fun publicOrUnparsableAddressesDoNot() {
        assertFalse(looksLikeLocalNetwork("https://t3.example.com"))
        assertFalse(looksLikeLocalNetwork("https://8.8.8.8"))
        assertFalse(looksLikeLocalNetwork("t3code://app/pair#token=ABC"))
        assertFalse(looksLikeLocalNetwork(""))
    }
}
