package com.synapse.orderrouter.model

/**
 * Normalizes a category label for matching. Products and suppliers must use the
 * same rule, since the source data mixes casing/spacing (e.g. "CPAP" vs "cpap").
 * This is the single source of truth for both sides of that comparison.
 */
fun normalizeCategory(raw: String): String = raw.trim().lowercase()

/**
 * A product from products.csv. [categoryKey] is the normalized category used for
 * matching against supplier capabilities.
 */
data class Product(
    val code: String,
    val name: String,
    val category: String,
) {
    val categoryKey: String = normalizeCategory(category)
}
