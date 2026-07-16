package com.synapse.orderrouter.service

import com.synapse.orderrouter.model.Product
import com.synapse.orderrouter.model.RouteRequest
import com.synapse.orderrouter.model.RouteResponse
import com.synapse.orderrouter.model.RoutedItem
import com.synapse.orderrouter.model.Supplier
import com.synapse.orderrouter.model.SupplierShipment
import com.synapse.orderrouter.repository.ProductCatalog
import com.synapse.orderrouter.repository.SupplierDirectory
import org.springframework.stereotype.Service

/**
 * Routes an order to one or more suppliers.
 *
 * Optimizes, in the priority order from the requirements:
 *   1. Feasibility  — only suppliers that can actually fulfill an item.
 *   2. Consolidation — fewest shipments (a greedy set-cover over suppliers).
 *   3. Quality       — higher customer-satisfaction suppliers preferred.
 *   4. Geography     — local fulfillment preferred over mail order on ties.
 *
 * The endpoint always returns HTTP 200; [RouteResponse.feasible] reports whether
 * the order could be fully routed.
 */
@Service
class RoutingService(
    private val productCatalog: ProductCatalog,
    private val supplierDirectory: SupplierDirectory,
) {
    companion object {
        /** Upper bound on a single line item's quantity (parameter-size guard). */
        const val MAX_QUANTITY = 1_000

        /** Ranking value used for suppliers with no satisfaction rating yet. */
        private const val UNRATED_SCORE = 0.0

        private const val MODE_LOCAL = "local"
        private const val MODE_MAIL = "mail_order"

        private val ZIP_REGEX = Regex("""\d{5}""")
    }

    /** An order line item successfully resolved to a known [Product]. */
    private data class ResolvedItem(val productCode: String, val quantity: Int, val product: Product)

    /** A supplier together with the remaining items it can fulfill and how. */
    private data class Candidate(
        val supplier: Supplier,
        val entries: List<Entry>,
    ) {
        data class Entry(val item: ResolvedItem, val mode: String)

        val coveredCount: Int get() = entries.size
        val localCount: Int get() = entries.count { it.mode == MODE_LOCAL }
        val effectiveScore: Double get() = supplier.satisfaction ?: UNRATED_SCORE
    }

    fun route(request: RouteRequest): RouteResponse {
        val validationErrors = validate(request)
        if (validationErrors.isNotEmpty()) {
            return RouteResponse.failure(validationErrors, request.orderId)
        }

        // Resolve every line item to a known product. Unknown codes are recorded
        // but do not stop us from routing the items we *can* place.
        val errors = mutableListOf<String>()
        val resolved = mutableListOf<ResolvedItem>()
        for (item in request.items!!) {
            val product = productCatalog.findByCode(item.productCode!!)
            if (product == null) {
                errors.add("Unknown product code: ${item.productCode}")
            } else {
                resolved.add(ResolvedItem(item.productCode, item.quantity!!, product))
            }
        }

        val (shipments, unassigned) = assign(resolved, request)

        for (item in unassigned) {
            errors.add(
                "No supplier can fulfill product ${item.productCode} " +
                    "(category '${item.product.category}') for the given ZIP/mail-order options.",
            )
        }

        val routing = shipments.takeIf { it.isNotEmpty() }
        return if (errors.isEmpty()) {
            RouteResponse.success(shipments, request.orderId)
        } else {
            RouteResponse.failure(errors, request.orderId, routing)
        }
    }

    /**
     * Greedy set-cover: repeatedly assign the remaining items to the single
     * best supplier until everything is placed or no supplier can help.
     */
    private fun assign(
        resolved: List<ResolvedItem>,
        request: RouteRequest,
    ): Pair<List<SupplierShipment>, List<ResolvedItem>> {
        val remaining = resolved.toMutableList()
        val shipments = mutableListOf<SupplierShipment>()
        // Sort suppliers by id so ties resolve deterministically (earliest id wins).
        val suppliers = supplierDirectory.all().sortedBy { it.id }

        while (remaining.isNotEmpty()) {
            val best = suppliers
                .map { candidateFor(it, remaining, request) }
                .filter { it.coveredCount > 0 }
                .maxWithOrNull(preference)
                ?: break // no supplier can fulfill any remaining item

            shipments.add(best.toShipment())
            remaining.removeAll(best.entries.map { it.item }.toSet())
        }
        return shipments to remaining
    }

    /** Which of [items] the given supplier can fulfill, and in what mode. */
    private fun candidateFor(supplier: Supplier, items: List<ResolvedItem>, request: RouteRequest): Candidate {
        val servesZip = request.customerZip?.let { supplier.servesZip(it) } ?: false
        val entries = items.mapNotNull { item ->
            if (!supplier.handlesCategory(item.product.categoryKey)) return@mapNotNull null
            val mode = when {
                servesZip -> MODE_LOCAL
                request.mailOrder && supplier.canMailOrder -> MODE_MAIL
                else -> return@mapNotNull null // not eligible geographically
            }
            Candidate.Entry(item, mode)
        }
        return Candidate(supplier, entries)
    }

    private fun Candidate.toShipment(): SupplierShipment = SupplierShipment(
        supplierId = supplier.id,
        supplierName = supplier.name,
        satisfactionScore = supplier.satisfaction,
        items = entries.map {
            RoutedItem(
                productCode = it.item.productCode,
                quantity = it.item.quantity,
                category = it.item.product.category,
                fulfillmentMode = it.mode,
            )
        },
    )

    /**
     * "Better" candidate ordering (greatest wins): more items covered, then
     * higher satisfaction, then more locally-fulfilled items.
     */
    private val preference: Comparator<Candidate> = compareBy(
        { it.coveredCount },
        { it.effectiveScore },
        { it.localCount },
    )

    private fun validate(request: RouteRequest): List<String> {
        val errors = mutableListOf<String>()

        val items = request.items
        if (items.isNullOrEmpty()) {
            errors.add("Order must include at least one line item.")
        }

        val zip = request.customerZip
        if (zip.isNullOrBlank()) {
            errors.add("Order must include a valid customer_zip.")
        } else if (!ZIP_REGEX.matches(zip.trim())) {
            errors.add("customer_zip must be a 5-digit ZIP code (got '$zip').")
        }

        items?.forEachIndexed { i, item ->
            val label = item.productCode?.takeIf { it.isNotBlank() } ?: "item #${i + 1}"
            if (item.productCode.isNullOrBlank()) {
                errors.add("Line $label must include a product_code.")
            }
            when {
                item.quantity == null -> errors.add("Line $label must include a quantity.")
                item.quantity < 1 -> errors.add("Line $label quantity must be at least 1 (got ${item.quantity}).")
                item.quantity > MAX_QUANTITY ->
                    errors.add("Line $label quantity ${item.quantity} exceeds maximum of $MAX_QUANTITY.")
            }
        }

        return errors.distinct()
    }
}
