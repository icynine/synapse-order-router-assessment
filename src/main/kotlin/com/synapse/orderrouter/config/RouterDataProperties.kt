package com.synapse.orderrouter.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Locations of the reference data files, bound from the `router.data.*` keys in
 * application.yml. Values are Spring resource locations (e.g. `classpath:...`
 * or `file:...`) so alternate datasets can be supplied without a rebuild.
 */
@ConfigurationProperties(prefix = "router.data")
data class RouterDataProperties(
    val products: String = "classpath:data/products.csv",
    val suppliers: String = "classpath:data/suppliers.csv",
)
