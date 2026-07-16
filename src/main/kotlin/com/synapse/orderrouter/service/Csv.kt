package com.synapse.orderrouter.service

import java.io.BufferedReader

/**
 * Minimal, dependency-free CSV parser good enough for the reference data.
 *
 * Supports double-quoted fields containing commas (e.g. service_zips and
 * product_categories cells) and escaped quotes (`""`). Assumes no embedded
 * newlines within fields, which holds for the provided files.
 */
object Csv {

    /** A parsed table: the header row plus data rows keyed by header name. */
    class Table(val headers: List<String>, val rows: List<List<String>>) {
        private val index: Map<String, Int> =
            headers.mapIndexed { i, h -> h.trim().lowercase() to i }.toMap()

        /**
         * Resolves a column value for [row], trying each candidate header name
         * in order. Tolerates the known header typo (`suplier_name`) by letting
         * callers pass fallbacks. Returns null when no candidate column exists.
         */
        fun value(row: List<String>, vararg headerCandidates: String): String? {
            for (candidate in headerCandidates) {
                val i = index[candidate.trim().lowercase()] ?: continue
                return row.getOrNull(i) // parseLine already trims each field
            }
            return null
        }
    }

    fun parse(reader: BufferedReader): Table {
        val lines = reader.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return Table(emptyList(), emptyList())
        val headers = parseLine(lines.first())
        val rows = lines.drop(1).map { parseLine(it) }
        return Table(headers, rows)
    }

    /** Splits a single CSV line into fields, honoring double-quoted sections. */
    fun parseLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"') // escaped quote
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields.map { it.trim() }
    }
}
