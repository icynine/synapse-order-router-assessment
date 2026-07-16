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

    /**
     * A supplier eligible for this order, paired with its fulfillment mode.
     * [local] is true when the supplier serves the customer's ZIP; it is uniform
     * across all of the supplier's items (mode depends on supplier + order, not
     * on the individual item). Computed once per order.
     */
    private class EligibleSupplier(val supplier: Supplier, val local: Boolean)

    /** An eligible supplier together with the remaining items it can cover. */
    private class Candidate(val eligible: EligibleSupplier, val items: List<ResolvedItem>) {
        val coveredCount: Int get() = items.size
        val local: Boolean get() = eligible.local
        val effectiveScore: Double get() = eligible.supplier.satisfaction ?: UNRATED_SCORE
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
        for (item in request.items.orEmpty()) {
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
        if (resolved.isEmpty()) return emptyList<SupplierShipment>() to emptyList()

        val eligible = eligibleSuppliers(resolved, request)
        val remaining = resolved.toMutableList()
        val shipments = mutableListOf<SupplierShipment>()

        while (remaining.isNotEmpty()) {
            val best = eligible
                .map { candidate -> Candidate(candidate, remaining.filter { candidate.supplier.handlesCategory(it.product.categoryKey) }) }
                .filter { it.coveredCount > 0 }
                .maxWithOrNull(preference)
                ?: break // no eligible supplier can fulfill any remaining item

            shipments.add(best.toShipment())
            remaining.removeAll(best.items.toSet())
        }
        return shipments to remaining
    }

    /**
     * The suppliers eligible for this order — those handling at least one
     * requested category and reachable (serve the ZIP, or ship nationally when
     * mail order is allowed) — each paired with its fulfillment mode. Computed
     * once per order and sorted by id so ties resolve deterministically. Only
     * suppliers handling a requested category are considered, via the catalog
     * index, rather than scanning the whole directory.
     */
    private fun eligibleSuppliers(resolved: List<ResolvedItem>, request: RouteRequest): List<EligibleSupplier> {
        val zip = request.customerZip!! // validated present before assignment
        val categories = resolved.mapTo(mutableSetOf()) { it.product.categoryKey }
        return categories
            .flatMap { supplierDirectory.handling(it) }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .mapNotNull { supplier ->
                when {
                    supplier.servesZip(zip) -> EligibleSupplier(supplier, local = true)
                    request.mailOrder && supplier.canMailOrder -> EligibleSupplier(supplier, local = false)
                    else -> null // not reachable for this order
                }
            }
    }

    private fun Candidate.toShipment(): SupplierShipment {
        val mode = if (local) MODE_LOCAL else MODE_MAIL
        return SupplierShipment(
            supplierId = eligible.supplier.id,
            supplierName = eligible.supplier.name,
            satisfactionScore = eligible.supplier.satisfaction,
            items = items.map {
                RoutedItem(
                    productCode = it.productCode,
                    quantity = it.quantity,
                    category = it.product.category,
                    fulfillmentMode = mode,
                )
            },
        )
    }

    /**
     * "Better" candidate ordering (greatest wins): more items covered, then
     * higher satisfaction, then local preferred over mail order (true > false).
     */
    private val preference: Comparator<Candidate> = compareBy(
        { it.coveredCount },
        { it.effectiveScore },
        { it.local },
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
