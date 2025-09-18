package com.mcp.infrastructure.web

import com.mcp.infrastructure.scheduler.McpConnectionWatchdog
import io.modelcontextprotocol.client.McpSyncClient
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/mcp-debug")
class McpDebugController(
    private val mcpClients: List<McpSyncClient>,
    private val watchdog: McpConnectionWatchdog
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/init")
    fun init(): Map<String, String> =
        mcpClients.associate { c ->
            runCatching { c.initialize(); c.toString() to "initialized" }
                .getOrElse { ex -> c.toString() to "failed: ${ex.message}" }
        }

    @GetMapping("/clients")
    fun clients(): List<String> =
        mcpClients.map { it.toString() }

    @GetMapping("/tools")
    fun tools(): Map<String, Any> {

        return mcpClients.associate { c -> c.toString() to c.listTools().tools.map { it.name } }
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
    
    // WatchDog 관련 엔드포인트들
    @GetMapping("/health")
    fun health(): Map<String, Any> = watchdog.getHealthStatus()
    
    @PostMapping("/reconnect")
    fun reconnectAll(): Map<String, String> {
        watchdog.forceReconnectAll()
        return mapOf("status" to "reconnection attempted for all clients")
    }
    
    @PostMapping("/reconnect/{clientId}")
    fun reconnectSpecific(@PathVariable clientId: String): Map<String, String> {
        watchdog.forceReconnect(clientId)
        return mapOf("status" to "reconnection attempted for client: $clientId")
    }
    
    @PostMapping("/restart-recommendation")
    fun getRestartRecommendation(): Map<String, Any> {
        val healthStatus = watchdog.getHealthStatus()
        val unhealthyClients = healthStatus.filter { (_, status) ->
            val statusMap = status as Map<*, *>
            statusMap["healthy"] == false
        }
        
        return if (unhealthyClients.isNotEmpty()) {
            mapOf(
                "recommendation" to "RESTART_REQUIRED",
                "message" to "MCP reconnection failed. Application restart recommended.",
                "unhealthyClients" to unhealthyClients.keys,
                "action" to "Please restart the application to restore MCP connections",
                "severity" to "HIGH"
            )
        } else {
            mapOf(
                "recommendation" to "HEALTHY",
                "message" to "All MCP clients are healthy",
                "action" to "No action required",
                "severity" to "LOW"
            )
        }
    }
}