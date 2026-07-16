package com.synapse.orderrouter.model

/**
 * A supplier from suppliers.csv.
 *
 * @param categoryKeys normalized (lower-cased) categories this supplier handles.
 * @param satisfaction customer satisfaction score 1-10, or null when the source
 *   value was "no ratings yet".
 * @param canMailOrder whether the supplier ships nationally (`can_mail_order? = y`).
 */
data class Supplier(
    val id: String,
    val name: String,
    val serviceZips: ZipCoverage,
    val categoryKeys: Set<String>,
    val satisfaction: Double?,
    val canMailOrder: Boolean,
) {
    /** True when this supplier can fulfill the given (already-normalized) category. */
    fun handlesCategory(categoryKey: String): Boolean = categoryKey in categoryKeys

    /** True when this supplier's service area covers [zip]. */
    fun servesZip(zip: String): Boolean = serviceZips.covers(zip)
}
