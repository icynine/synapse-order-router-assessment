package com.synapse.orderrouter.service

import com.synapse.orderrouter.config.RouterDataProperties
import com.synapse.orderrouter.model.OrderItem
import com.synapse.orderrouter.model.RouteRequest
import com.synapse.orderrouter.repository.ProductCatalog
import com.synapse.orderrouter.repository.SupplierDirectory
import org.springframework.core.io.DefaultResourceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Routing logic tests against a small, controlled dataset (testdata CSVs) so
 * outcomes are deterministic. Suppliers are designed to exercise consolidation,
 * quality tie-breaks, mail-order eligibility, and infeasibility.
 */
class RoutingServiceTest {

    private val service: RoutingService = buildService()

    private fun buildService(): RoutingService {
        val loader = DefaultResourceLoader()
        val props = RouterDataProperties(
            products = "classpath:testdata/products_test.csv",
            suppliers = "classpath:testdata/suppliers_test.csv",
        )
        return RoutingService(ProductCatalog(loader, props), SupplierDirectory(loader, props))
    }

    private fun order(
        zip: String? = "10015",
        mailOrder: Boolean = false,
        vararg items: Pair<String, Int>,
        id: String = "TEST",
    ) = RouteRequest(
        orderId = id,
        customerZip = zip,
        mailOrder = mailOrder,
        items = items.map { OrderItem(it.first, it.second) },
    )

    // --- Consolidation -----------------------------------------------------

    @Test
    fun `consolidates multi-category order into a single supplier when possible`() {
        val response = service.route(order(items = arrayOf("WC-1" to 1, "OX-1" to 1)))

        assertTrue(response.feasible)
        assertEquals(1, response.routing!!.size, "should ship from one supplier")
        val shipment = response.routing!!.first()
        assertEquals("SUP-A", shipment.supplierId) // covers both categories, rating 9
        assertEquals(2, shipment.items.size)
        assertTrue(shipment.items.all { it.fulfillmentMode == "local" })
    }

    @Test
    fun `splits into multiple shipments when no single supplier covers all`() {
        // wheelchair (SUP-A/B/E) + CPAP (SUP-F local); no supplier does both.
        val response = service.route(order(items = arrayOf("WC-1" to 1, "CP-1" to 1)))

        assertTrue(response.feasible)
        assertEquals(2, response.routing!!.size)
    }

    // --- Quality -----------------------------------------------------------

    @Test
    fun `prefers higher-rated supplier on an equal-coverage tie`() {
        // wheelchair alone: SUP-A(9), SUP-B(5), SUP-E(unrated) all serve 10015.
        val response = service.route(order(items = arrayOf("WC-1" to 1)))

        assertEquals("SUP-A", response.routing!!.first().supplierId)
    }

    // --- Mail order & geography -------------------------------------------

    @Test
    fun `uses mail-order supplier when local coverage is absent and mail is allowed`() {
        val response = service.route(
            order(zip = "99999", mailOrder = true, items = arrayOf("CP-1" to 1)),
        )

        assertTrue(response.feasible)
        val shipment = response.routing!!.first()
        assertEquals("SUP-D", shipment.supplierId) // national CPAP supplier
        assertEquals("mail_order", shipment.items.first().fulfillmentMode)
    }

    @Test
    fun `prefers local over mail-order when ratings are equal`() {
        // CPAP at 10015 with mail allowed: SUP-F local(7) vs SUP-D mail(7).
        val response = service.route(
            order(zip = "10015", mailOrder = true, items = arrayOf("CP-1" to 1)),
        )

        val shipment = response.routing!!.first()
        assertEquals("SUP-F", shipment.supplierId)
        assertEquals("local", shipment.items.first().fulfillmentMode)
    }

    @Test
    fun `matches categories case-insensitively`() {
        // Product category is "CPAP"; SUP-F lists it as "cpap".
        val response = service.route(order(zip = "10015", items = arrayOf("CP-1" to 1)))
        assertTrue(response.feasible)
        assertEquals("SUP-F", response.routing!!.first().supplierId)
    }

    // --- Infeasibility -----------------------------------------------------

    @Test
    fun `is infeasible when no supplier serves the ZIP and mail is disabled`() {
        val response = service.route(
            order(zip = "99999", mailOrder = false, items = arrayOf("CP-1" to 1)),
        )

        assertFalse(response.feasible)
        assertNotNull(response.errors)
        assertTrue(response.errors!!.any { it.contains("CP-1") })
    }

    @Test
    fun `routes feasible items and reports the unroutable ones on a partial order`() {
        // WC-1 routable locally; CP-1 not routable (mail disabled, no local CPAP served at 20001).
        val response = service.route(
            order(zip = "10015", mailOrder = false, items = arrayOf("WC-1" to 1, "NB-1" to 1)),
        )
        // NB-1 (nebulizer) only offered by SUP-D which serves 90001 / mail; not eligible here.
        assertFalse(response.feasible)
        assertNotNull(response.routing) // WC-1 still routed
        assertTrue(response.routing!!.flatMap { it.items }.any { it.productCode == "WC-1" })
        assertTrue(response.errors!!.any { it.contains("NB-1") })
    }

    // --- Validation --------------------------------------------------------

    @Test
    fun `rejects order with no line items`() {
        val response = service.route(RouteRequest(orderId = "X", customerZip = "10015", items = emptyList()))
        assertFalse(response.feasible)
        assertTrue(response.errors!!.any { it.contains("at least one line item") })
    }

    @Test
    fun `rejects missing customer zip`() {
        val response = service.route(order(zip = null, items = arrayOf("WC-1" to 1)))
        assertFalse(response.feasible)
        assertTrue(response.errors!!.any { it.contains("customer_zip") })
    }

    @Test
    fun `rejects malformed zip`() {
        val response = service.route(order(zip = "ABC", items = arrayOf("WC-1" to 1)))
        assertFalse(response.feasible)
        assertTrue(response.errors!!.any { it.contains("5-digit") })
    }

    @Test
    fun `rejects unknown product code`() {
        val response = service.route(order(items = arrayOf("DOES-NOT-EXIST" to 1)))
        assertFalse(response.feasible)
        assertTrue(response.errors!!.any { it.contains("Unknown product code") })
    }

    @Test
    fun `rejects zero and negative quantity`() {
        assertTrue(
            service.route(order(items = arrayOf("WC-1" to 0)))
                .errors!!.any { it.contains("at least 1") },
        )
        assertTrue(
            service.route(order(items = arrayOf("WC-1" to -3)))
                .errors!!.any { it.contains("at least 1") },
        )
    }

    @Test
    fun `rejects quantity above the maximum`() {
        val response = service.route(order(items = arrayOf("WC-1" to RoutingService.MAX_QUANTITY + 1)))
        assertFalse(response.feasible)
        assertTrue(response.errors!!.any { it.contains("exceeds maximum") })
    }

    @Test
    fun `accepts quantity exactly at the maximum`() {
        val response = service.route(order(items = arrayOf("WC-1" to RoutingService.MAX_QUANTITY)))
        assertTrue(response.feasible)
        assertEquals(RoutingService.MAX_QUANTITY, response.routing!!.first().items.first().quantity)
    }
}
