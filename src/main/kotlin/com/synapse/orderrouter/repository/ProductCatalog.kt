package com.synapse.orderrouter.repository

import com.synapse.orderrouter.config.RouterDataProperties
import com.synapse.orderrouter.model.Product
import com.synapse.orderrouter.service.Csv
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Repository

/**
 * In-memory catalog of products keyed by product code, loaded once at startup
 * from products.csv. Lookups are case-insensitive on the product code.
 */
@Repository
class ProductCatalog(
    private val resourceLoader: ResourceLoader,
    private val properties: RouterDataProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byCode: Map<String, Product> = load()

    fun findByCode(code: String): Product? = byCode[code.trim().uppercase()]

    val size: Int get() = byCode.size

    private fun load(): Map<String, Product> {
        val resource = resourceLoader.getResource(properties.products)
        require(resource.exists()) { "Products file not found: ${properties.products}" }

        val table = resource.inputStream.bufferedReader().use { Csv.parse(it) }
        val products = table.rows.mapNotNull { row ->
            val code = table.value(row, "product_code")
            val category = table.value(row, "category")
            if (code.isNullOrBlank() || category.isNullOrBlank()) return@mapNotNull null
            Product(
                code = code.uppercase(),
                name = table.value(row, "product_name").orEmpty(),
                category = category,
            )
        }
        val map = products.associateBy { it.code }
        log.info("Loaded {} products from {}", map.size, properties.products)
        return map
    }
}
