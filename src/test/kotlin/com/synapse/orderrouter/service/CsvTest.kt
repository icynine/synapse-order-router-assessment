package com.synapse.orderrouter.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CsvTest {

    @Test
    fun `parses simple comma-separated line`() {
        assertEquals(listOf("a", "b", "c"), Csv.parseLine("a,b,c"))
    }

    @Test
    fun `keeps commas inside quoted fields`() {
        val fields = Csv.parseLine("\"10001, 10002, 10003\",oxygen")
        assertEquals(listOf("10001, 10002, 10003", "oxygen"), fields)
    }

    @Test
    fun `handles escaped double quotes`() {
        val fields = Csv.parseLine("\"say \"\"hi\"\"\",next")
        assertEquals(listOf("say \"hi\"", "next"), fields)
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(listOf("a", "b"), Csv.parseLine(" a , b "))
    }

    @Test
    fun `table value falls back across header candidates for typo tolerance`() {
        val csv = """
            supplier_id,suplier_name,can_mail_order?
            SUP-001,Acme Medical,y
        """.trimIndent()
        val table = Csv.parse(csv.reader().buffered())
        val row = table.rows.first()

        // Real header is the misspelled "suplier_name"; the correct spelling
        // is tried first, then the typo fallback.
        assertEquals("Acme Medical", table.value(row, "supplier_name", "suplier_name"))
        assertEquals("y", table.value(row, "can_mail_order?"))
        assertNull(table.value(row, "nonexistent_column"))
    }

    @Test
    fun `parse ignores blank lines`() {
        val csv = "h1,h2\n\na,b\n\n"
        val table = Csv.parse(csv.reader().buffered())
        assertEquals(1, table.rows.size)
    }
}
