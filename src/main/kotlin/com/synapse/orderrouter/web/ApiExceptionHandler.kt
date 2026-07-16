package com.synapse.orderrouter.web

import com.synapse.orderrouter.model.RouteResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Upholds the routing API's contract that `POST /api/route` always returns HTTP
 * 200: failures surface as a feasible=false [RouteResponse] rather than a 4xx/5xx.
 *
 * A malformed/unreadable body gets a specific explanation; any other unexpected
 * failure is logged (so it is not silently swallowed) and returned as a generic
 * feasible=false response. Scoped to [RouteController] so the HTML endpoints keep
 * standard error handling.
 */
@RestControllerAdvice(assignableTypes = [RouteController::class])
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleUnreadable(ex: HttpMessageNotReadableException): RouteResponse =
        RouteResponse.failure(listOf("Malformed request body: could not parse JSON order."))

    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleUnexpected(ex: Exception): RouteResponse {
        log.error("Unexpected error handling /api/route request", ex)
        return RouteResponse.failure(listOf("Unexpected error while routing the order."))
    }
}
