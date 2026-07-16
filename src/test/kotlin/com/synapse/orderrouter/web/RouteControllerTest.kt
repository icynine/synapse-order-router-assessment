package com.synapse.orderrouter.web

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test

/**
 * Full-stack tests for POST /api/route against the real bundled reference data,
 * verifying the HTTP contract (always 200) and JSON shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RouteControllerTest(@Autowired val mockMvc: MockMvc) {

    @Test
    fun `routes a valid order and returns feasible true`() {
        val body = """
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

        mockMvc.post("/api/route") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isOk() }
            jsonPath("$.feasible") { value(true) }
            jsonPath("$.order_id") { value("ORD-001") }
            jsonPath("$.routing") { isNotEmpty() }
            jsonPath("$.routing[0].supplier_id") { exists() }
            jsonPath("$.routing[0].items[0].fulfillment_mode") { exists() }
        }
    }

    @Test
    fun `returns 200 with feasible false and errors for an invalid order`() {
        mockMvc.post("/api/route") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "order_id": "BAD", "items": [] }"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.feasible") { value(false) }
            jsonPath("$.errors") { isNotEmpty() }
        }
    }

    @Test
    fun `returns 200 with an error for a malformed JSON body`() {
        mockMvc.post("/api/route") {
            contentType = MediaType.APPLICATION_JSON
            content = "{ this is not valid json"
        }.andExpect {
            status { isOk() }
            jsonPath("$.feasible") { value(false) }
            jsonPath("$.errors[0]") { value("Malformed request body: could not parse JSON order.") }
        }
    }

    @Test
    fun `serves the Thymeleaf test console at root`() {
        mockMvc.get("/").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
        }
    }
}
