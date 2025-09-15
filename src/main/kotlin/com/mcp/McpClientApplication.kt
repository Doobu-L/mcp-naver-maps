package com.mcp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class McpClientApplication

fun main(args: Array<String>) {
    runApplication<McpClientApplication>(*args)
}