package com.layerbit.ravam.jurisdiction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JurisdictionResolverTest {

    @Test
    fun `plain India call records automatically`() {
        val r = JurisdictionResolver.resolve(simNetwork = "IN", simHome = "IN")
        assertEquals(ConsentRule.ONE_PARTY, r.rule)
        assertEquals(RecordingMode.AUTO, r.mode)
        assertEquals("IN", r.decidingCountry)
        assertTrue(r.verified)
    }

    @Test
    fun `the dual-SIM Gulf case asks first, and names the UAE`() {
        // The scenario the whole resolver exists for: Indian SIM, physically in India,
        // dialling a UAE number. Lawful in India, chargeable in the UAE — so: ask.
        val r = JurisdictionResolver.resolve(
            simNetwork = "IN",          // on the Jio network, in India
            simHome = "IN",             // Indian SIM
            dialledNumberCountry = "AE", // calling a +971 number
        )
        assertEquals(ConsentRule.ALL_PARTY, r.rule)
        assertEquals(RecordingMode.ASK, r.mode)
        assertEquals("AE", r.decidingCountry)
    }

    @Test
    fun `a UAE SIM roaming in India is still treated as all-party`() {
        val r = JurisdictionResolver.resolve(simNetwork = "IN", simHome = "AE")
        assertEquals(ConsentRule.ALL_PARTY, r.rule)
        assertEquals("AE", r.decidingCountry)
    }

    @Test
    fun `an unknown country is treated as all-party, not waved through`() {
        val r = JurisdictionResolver.resolve(simNetwork = "ZZ")
        assertEquals(ConsentRule.UNCLEAR, r.rule)
        assertEquals(RecordingMode.ASK, r.mode)
        assertFalse(r.verified)
    }

    @Test
    fun `the United States is unclear, because its states disagree`() {
        val r = JurisdictionResolver.resolve(simNetwork = "US", simHome = "US")
        assertEquals(ConsentRule.UNCLEAR, r.rule)
        assertEquals(RecordingMode.ASK, r.mode)
    }

    @Test
    fun `no signals at all means ask, never assume safe`() {
        val r = JurisdictionResolver.resolve()
        assertEquals(ConsentRule.UNCLEAR, r.rule)
        assertEquals(RecordingMode.ASK, r.mode)
        assertEquals(null, r.decidingCountry)
    }

    @Test
    fun `one all-party signal among several one-party signals still wins`() {
        val r = JurisdictionResolver.resolve(
            simNetwork = "IN", simHome = "IN", dialledNumberCountry = "IN", deviceRegion = "DE",
        )
        assertEquals(ConsentRule.ALL_PARTY, r.rule)
        assertEquals("DE", r.decidingCountry)
    }

    @Test
    fun `all-one-party call names a country rather than leaving it blank`() {
        val r = JurisdictionResolver.resolve(simNetwork = "IN", dialledNumberCountry = "GB")
        assertEquals(ConsentRule.ONE_PARTY, r.rule)
        assertTrue(r.decidingCountry == "IN" || r.decidingCountry == "GB")
    }

    @Test
    fun `blank and null signals are ignored, not treated as unknown countries`() {
        val r = JurisdictionResolver.resolve(simNetwork = "", simHome = null, dialledNumberCountry = "IN")
        assertEquals(ConsentRule.ONE_PARTY, r.rule)
        assertEquals("IN", r.decidingCountry)
    }
}
