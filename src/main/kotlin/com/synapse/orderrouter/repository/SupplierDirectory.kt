package com.synapse.orderrouter.repository

import com.synapse.orderrouter.config.RouterDataProperties
import com.synapse.orderrouter.model.Supplier
import com.synapse.orderrouter.model.ZipCoverage
import com.synapse.orderrouter.model.normalizeCategory
import com.synapse.orderrouter.service.Csv
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Repository

/**
 * In-memory directory of suppliers, loaded once at startup from suppliers.csv.
 *
 * Handles the data's known quirks: a misspelled `suplier_name` header, ZIP
 * coverage expressed as lists and/or ranges, "no ratings yet" satisfaction
 * values, and mixed-case category names.
 */
@Repository
class SupplierDirectory(
    private val resourceLoader: ResourceLoader,
    private val properties: RouterDataProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val suppliers: List<Supplier> = load()

    /**
     * Category → suppliers index, each list pre-sorted by id so routing ties
     * resolve deterministically. Lets the router consider only relevant
     * suppliers instead of scanning the whole directory per request.
     */
    private val byCategory: Map<String, List<Supplier>> =
        suppliers.flatMap { supplier -> supplier.categoryKeys.map { it to supplier } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedBy { it.id } }

    /** Suppliers that handle the given (normalized) category, sorted by id. */
    fun handling(categoryKey: String): List<Supplier> = byCategory[categoryKey].orEmpty()

    val size: Int get() = suppliers.size

    private fun load(): List<Supplier> {
        val resource = resourceLoader.getResource(properties.suppliers)
        require(resource.exists()) { "Suppliers file not found: ${properties.suppliers}" }

        val table = resource.inputStream.bufferedReader().use { Csv.parse(it) }
        val suppliers = table.rows.mapNotNull { row ->
            val id = table.value(row, "supplier_id")
            if (id.isNullOrBlank()) return@mapNotNull null
            Supplier(
                id = id,
                // Tolerate the header typo present in the source file.
                name = table.value(row, "supplier_name", "suplier_name").orEmpty(),
                serviceZips = ZipCoverage.parse(table.value(row, "service_zips")),
                categoryKeys = parseCategories(table.value(row, "product_categories")),
                satisfaction = parseSatisfaction(table.value(row, "customer_satisfaction_score")),
                canMailOrder = parseYesNo(table.value(row, "can_mail_order?", "can_mail_order")),
            )
        }
        log.info("Loaded {} suppliers from {}", suppliers.size, properties.suppliers)
        return suppliers
    }

    private fun parseCategories(raw: String?): Set<String> =
        raw?.split(",")
            ?.map { normalizeCategory(it) }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    /** Returns the numeric score, or null for "no ratings yet"/blank/unparseable. */
    private fun parseSatisfaction(raw: String?): Double? = raw?.trim()?.toDoubleOrNull()

    private fun parseYesNo(raw: String?): Boolean = raw?.trim()?.lowercase() == "y"
}
