package com.synapse.orderrouter

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class OrderRouterApplication

fun main(args: Array<String>) {
    runApplication<OrderRouterApplication>(*args)
}
