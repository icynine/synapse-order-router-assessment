package com.synapse.orderrouter.web

import com.synapse.orderrouter.repository.ProductCatalog
import com.synapse.orderrouter.repository.SupplierDirectory
import com.synapse.orderrouter.service.RoutingService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Serves the single-page Thymeleaf test harness at `/`. The page itself calls
 * the JSON API from the browser; this controller just supplies display context
 * (dataset sizes, a sample order, and a ready-to-copy curl command).
 */
@Controller
class WebController(
    private val supplierDirectory: SupplierDirectory,
    private val productCatalog: ProductCatalog,
) {

    @GetMapping("/")
    fun index(model: Model): String {
        model.addAttribute("supplierCount", supplierDirectory.size)
        model.addAttribute("productCount", productCatalog.size)
        model.addAttribute("maxQuantity", RoutingService.MAX_QUANTITY)
        model.addAttribute("sampleOrderJson", SAMPLE_ORDER_JSON)
        model.addAttribute("curlCommand", CURL_COMMAND)
        return "index"
    }

    companion object {
        private val SAMPLE_ORDER_JSON = """
            {
              "order_id": "ORD-001",
              "customer_zip": "10015",
              "mail_order": false,
              "items": [
                { "product_code": "WC-STD-001", "quantity": 1 },
                { "product_code": "OX-PORT-024", "quantity": 1 }
              ]
            }
        """.trimIndent()

        private val CURL_COMMAND = """
            curl -s -X POST http://localhost:8080/api/route \
              -H 'Content-Type: application/json' \
              -d '{
                "order_id": "ORD-001",
                "customer_zip": "10015",
                "mail_order": false,
                "items": [
                  { "product_code": "WC-STD-001", "quantity": 1 },
                  { "product_code": "OX-PORT-024", "quantity": 1 }
                ]
              }'
        """.trimIndent()
    }
}
