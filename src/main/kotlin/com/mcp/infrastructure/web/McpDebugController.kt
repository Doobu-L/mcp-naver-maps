package com.mcp.infrastructure.web

import io.modelcontextprotocol.client.McpSyncClient
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/mcp-debug")
class McpDebugController(
    private val mcpClients: List<McpSyncClient>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/clients")
    fun clients(): List<String> =
        mcpClients.map { it.toString() }

    @GetMapping("/tools")
    fun tools(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        mcpClients.forEach { client ->
            val tools = client.listTools().tools
            result[client.toString()] = tools.map { 
                mapOf(
                    "name" to it.name,
                    "description" to it.description,
                    "inputSchema" to it.inputSchema
                )
            }
        }
        return result
    }

    @GetMapping("/tools-detailed")
    fun toolsDetailed(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        mcpClients.forEach { client ->
            try {
                val tools = client.listTools().tools
                result[client.toString()] = tools.map { tool ->
                    mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "inputSchema" to tool.inputSchema?.toString()
                    )
                }
            } catch (e: Exception) {
                log.error("도구 목록 조회 실패", e)
                result[client.toString()] = mapOf("error" to e.message)
            }
        }
        return result
    }
}