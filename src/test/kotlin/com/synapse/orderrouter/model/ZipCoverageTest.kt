package com.synapse.orderrouter.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZipCoverageTest {

    @Test
    fun `covers explicit list of zips`() {
        val coverage = ZipCoverage.parse("10001, 10002, 10003")
        assertTrue(coverage.covers("10002"))
        assertFalse(coverage.covers("10004"))
    }

    @Test
    fun `covers inclusive range`() {
        val coverage = ZipCoverage.parse("10001-10100")
        assertTrue(coverage.covers("10001")) // low bound inclusive
        assertTrue(coverage.covers("10050"))
        assertTrue(coverage.covers("10100")) // high bound inclusive
        assertFalse(coverage.covers("10101"))
    }

    @Test
    fun `covers mixed list and range in one cell`() {
        val coverage = ZipCoverage.parse("10001, 10500-10600, 20000")
        assertTrue(coverage.covers("10001"))
        assertTrue(coverage.covers("10550"))
        assertTrue(coverage.covers("20000"))
        assertFalse(coverage.covers("10300"))
    }

    @Test
    fun `handles leading zeros via numeric comparison`() {
        val coverage = ZipCoverage.parse("00100-02200")
        assertTrue(coverage.covers("02130"))
        assertFalse(coverage.covers("02201"))
    }

    @Test
    fun `blank or null input covers nothing`() {
        assertFalse(ZipCoverage.parse("").covers("10001"))
        assertFalse(ZipCoverage.parse(null).covers("10001"))
        assertFalse(ZipCoverage.parse("   ").covers("10001"))
    }

    @Test
    fun `skips unparseable tokens and inverted ranges without failing`() {
        val coverage = ZipCoverage.parse("abc, 10001, 10100-10000")
        assertEquals(1, coverage.rangeCount) // only 10001 survives
        assertTrue(coverage.covers("10001"))
    }

    @Test
    fun `non-numeric query zip does not match`() {
        assertFalse(ZipCoverage.parse("10001-10100").covers("ABCDE"))
    }
}
