package org.example

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PricingProviderTest {

    @BeforeTest
    fun resetCache() {
        PricingProvider.resetCache()
    }

    @Test
    fun `formatCost formats small amounts with 6 decimal places`() {
        assertEquals("\$0.000001", PricingProvider.formatCost(0.000001))
    }

    @Test
    fun `formatCost formats medium amounts with 4 decimal places`() {
        val result = PricingProvider.formatCost(0.0050)
        assertEquals("\$0.0050", result)
    }

    @Test
    fun `formatCost formats large amounts with 2 decimal places`() {
        assertEquals("\$1.23", PricingProvider.formatCost(1.234))
    }

    @Test
    fun `costUsd returns null for unknown model`() {
        val result = PricingProvider.costUsd("unknown-model-xyz-999", TokenUsage(100, 50))
        assertNull(result)
    }

    @Test
    fun `costUsd calculates positive cost for known model prefix`() {
        val usage = TokenUsage(inputTokens = 1_000_000, outputTokens = 1_000_000)
        val cost = PricingProvider.costUsd("claude-sonnet-4-6", usage)
        assertNotNull(cost)
        assertTrue(cost > 0.0)
    }

    @Test
    fun `getPricing returns pricing for known model prefix`() {
        val pricing = PricingProvider.getPricing("claude-sonnet-4-6")
        assertNotNull(pricing)
        assertTrue(pricing.inputCostPerToken > 0.0)
        assertTrue(pricing.outputCostPerToken > 0.0)
    }

    @Test
    fun `costUsd returns positive cost for haiku model`() {
        val usage = TokenUsage(inputTokens = 1_000_000, outputTokens = 1_000_000)
        val cost = PricingProvider.costUsd("claude-haiku-4-5-20251001", usage)
        assertNotNull(cost)
        assertTrue(cost > 0.0)
    }
}
