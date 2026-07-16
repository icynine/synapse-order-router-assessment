package com.synapse.orderrouter.web

import com.synapse.orderrouter.model.RouteRequest
import com.synapse.orderrouter.model.RouteResponse
import com.synapse.orderrouter.service.RoutingService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The order routing API.
 *
 * `POST /api/route` accepts a single order and always responds with HTTP 200;
 * the `feasible` flag in the body indicates whether routing succeeded.
 */
@RestController
@RequestMapping("/api")
class RouteController(private val routingService: RoutingService) {

    @PostMapping(
        "/route",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun route(@RequestBody request: RouteRequest): RouteResponse = routingService.route(request)
}
