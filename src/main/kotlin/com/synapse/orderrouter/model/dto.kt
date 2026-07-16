package com.synapse.orderrouter.model

/**
 * Request/response payloads for `POST /api/route`.
 *
 * JSON uses snake_case (e.g. `order_id`, `mail_order`); the mapping to these
 * camelCase properties is handled globally by Jackson's SNAKE_CASE naming
 * strategy (see application.yml). Null fields are omitted from responses.
 */

// ---------------------------------------------------------------------------
// Request
// ---------------------------------------------------------------------------

data class RouteRequest(
    val orderId: String? = null,
    val customerZip: String? = null,
    val mailOrder: Boolean = false,
    val items: List<OrderItem>? = null,
    val priority: String? = null,
    val notes: String? = null,
)

data class OrderItem(
    val productCode: String? = null,
    val quantity: Int? = null,
)

// ---------------------------------------------------------------------------
// Response
// ---------------------------------------------------------------------------

/**
 * Always returned with HTTP 200. [feasible] indicates whether the order could be
 * fully routed. On failure [errors] explains why; [routing] may still carry the
 * subset of items that could be placed.
 */
data class RouteResponse(
    val feasible: Boolean,
    val orderId: String? = null,
    val routing: List<SupplierShipment>? = null,
    val errors: List<String>? = null,
) {
    companion object {
        fun failure(errors: List<String>, orderId: String? = null, routing: List<SupplierShipment>? = null) =
            RouteResponse(feasible = false, orderId = orderId, routing = routing, errors = errors)

        fun success(routing: List<SupplierShipment>, orderId: String? = null) =
            RouteResponse(feasible = true, orderId = orderId, routing = routing)
    }
}

/** One shipment: the set of items assigned to a single supplier. */
data class SupplierShipment(
    val supplierId: String,
    val supplierName: String,
    val satisfactionScore: Double?,
    val items: List<RoutedItem>,
)

data class RoutedItem(
    val productCode: String,
    val quantity: Int,
    val category: String,
    /** "local" when the supplier serves the customer's ZIP, else "mail_order". */
    val fulfillmentMode: String,
)
