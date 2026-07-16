package com.synapse.orderrouter.web

import com.synapse.orderrouter.model.RouteResponse
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Keeps the routing API's contract that `POST /api/route` always returns HTTP
 * 200: a malformed/unreadable JSON body becomes a feasible=false response with
 * an explanatory error instead of a raw 400.
 */
@RestControllerAdvice(assignableTypes = [RouteController::class])
class ApiExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleUnreadable(ex: HttpMessageNotReadableException): RouteResponse =
        RouteResponse.failure(listOf("Malformed request body: could not parse JSON order."))
}
