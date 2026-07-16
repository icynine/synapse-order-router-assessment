package com.synapse.orderrouter.model

/**
 * The set of ZIP codes a supplier serves.
 *
 * The source data expresses coverage in two messy forms that can also be mixed
 * within a single cell:
 *   - explicit list:  "10001, 10002, 10003"
 *   - inclusive range: "10001-10100"
 *
 * ZIPs are compared numerically. Leading zeros (e.g. "02130") are preserved in
 * the raw request but do not affect comparison because they parse to the same
 * integer as the range bounds.
 */
class ZipCoverage private constructor(private val ranges: List<IntRange>) {

    /** True when [zip] falls inside any covered range. */
    fun covers(zip: String): Boolean {
        val value = zip.trim().toIntOrNull() ?: return false
        return ranges.any { value in it }
    }

    companion object {
        /**
         * Parses a raw `service_zips` cell into coverage. Tokens that cannot be
         * interpreted (blank, non-numeric, inverted range) are skipped rather
         * than aborting the whole supplier — the data is known to be messy.
         */
        fun parse(raw: String?): ZipCoverage {
            if (raw.isNullOrBlank()) return ZipCoverage(emptyList())
            val ranges = raw.split(",").mapNotNull { token ->
                val trimmed = token.trim()
                when {
                    trimmed.isEmpty() -> null
                    "-" in trimmed -> parseRange(trimmed)
                    else -> trimmed.toIntOrNull()?.let { it..it }
                }
            }
            return ZipCoverage(ranges)
        }

        private fun parseRange(token: String): IntRange? {
            val parts = token.split("-", limit = 2).map { it.trim() }
            val low = parts.getOrNull(0)?.toIntOrNull()
            val high = parts.getOrNull(1)?.toIntOrNull()
            if (low == null || high == null || low > high) return null
            return low..high
        }
    }
}
