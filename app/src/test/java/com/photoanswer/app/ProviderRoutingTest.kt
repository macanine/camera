package com.photoanswer.app

import org.junit.Assert.*
import org.junit.Test

class ProviderRoutingTest {
    private val primary = ProviderConfig("primary", "primary", "https://a.example/v1", "m", "test")
    private val secondary = primary.copy(id = "secondary", baseUrl = "https://b.example/v1", priority = 1)
    private val backup = primary.copy(id = "backup", baseUrl = "https://c.example/v1", priority = 2, fallbackOnly = true)

    @Test fun `production retry policy advances through eligible distinct connections`() {
        val providers = listOf(backup, secondary, primary)
        assertEquals(listOf(primary, secondary), RetryPolicy.candidates(providers, emptyMap(), emptySet()))
        assertEquals(listOf(secondary, backup), RetryPolicy.candidates(providers, mapOf("primary" to 1), emptySet()))
        assertEquals(listOf(backup), RetryPolicy.candidates(providers, mapOf("primary" to 1, "secondary" to 1), emptySet()))
        assertTrue(RetryPolicy.candidates(listOf(primary), mapOf("primary" to 1), emptySet()).isEmpty())
    }
    @Test fun `standby cannot take the initial slot while normal connection is full`() {
        val gate = RequestCoordinator { 100L }
        val providers = listOf(primary, backup)
        assertTrue(gate.acquire("owner", primary, providers))
        assertNull(RetryPolicy.candidates(providers, emptyMap(), emptySet()).firstOrNull { gate.acquire("next", it, providers) })
    }
    @Test fun `moving connection persists contiguous priorities`() {
        val moved = ProviderRouting.move(listOf(primary, secondary, backup), "backup", -1)
        assertEquals(listOf("primary", "backup", "secondary"), moved.map { it.id })
        assertEquals(listOf(0, 1, 2), moved.map { it.priority })
    }
    @Test fun `invalid endpoints are not routed`() {
        for (url in listOf("https://", "http://example.com", "https://user:pass@example.com", "https://example.com?key=secret")) {
            assertFalse(primary.copy(baseUrl = url).isConfigured)
        }
        assertTrue(primary.isConfigured)
        assertFalse(primary.copy(apiKey = "").isConfigured)
    }
}
