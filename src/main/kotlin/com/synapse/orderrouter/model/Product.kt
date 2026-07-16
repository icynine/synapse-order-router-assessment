package com.synapse.orderrouter.model

/**
 * A product from products.csv. [categoryKey] is the normalized (lower-cased,
 * trimmed) category used for matching against supplier capabilities, because
 * the source data mixes casing (e.g. "CPAP" vs "cpap").
 */
data class Product(
    val code: String,
    val name: String,
    val category: String,
) {
    val categoryKey: String = category.trim().lowercase()
}
