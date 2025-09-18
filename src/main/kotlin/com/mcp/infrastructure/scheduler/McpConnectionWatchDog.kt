package com.mcp.infrastructure.scheduler

import io.modelcontextprotocol.client.McpSyncClient
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

@EnableScheduling
@Component
class McpConnectionWatchdog(
    private val clients: List<McpSyncClient>,
    private val syncMcpToolCallbackProvider: SyncMcpToolCallbackProvider,
    private val applicationContext: ApplicationContext
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val clientHealthStatus = ConcurrentHashMap<String, Boolean>()
    private val clientReconnectAttempts = ConcurrentHashMap<String, Long>()
    
    // 15초마다 헬스 체크 (더 빠른 장애 감지)
    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    fun watch() {
        clients.forEach { client ->
            val clientId = client.toString()
            val wasHealthy = clientHealthStatus[clientId] ?: true
            
            val isHealthy = checkClientHealth(client)
            clientHealthStatus[clientId] = isHealthy
            
            when {
                !wasHealthy && isHealthy -> {
                    log.info("✅ MCP client recovered: {}", clientId)
                    clientReconnectAttempts.remove(clientId) // 복구되면 재시도 카운터 리셋
                }
                wasHealthy && !isHealthy -> {
                    log.warn("❌ MCP client became unhealthy: {}", clientId)
                    attemptReconnection(client)
                }
                !isHealthy -> {
                    // 연속으로 실패하는 경우 재연결 빈도를 줄임 (매번 시도하지 않음)
                    val lastAttempt = clientReconnectAttempts[clientId] ?: 0L
                    val now = System.currentTimeMillis()
                    if (now - lastAttempt > 60_000) { // 1분에 한 번만 재시도
                        log.debug("🔄 MCP client still unhealthy, retrying: {}", clientId)
                        clientReconnectAttempts[clientId] = now
                        attemptReconnection(client)
                    } else {
                        log.debug("⏳ MCP client unhealthy, waiting before retry: {}", clientId)
                    }
                }
                else -> {
                    log.debug("✅ MCP client healthy: {}", clientId)
                }
            }
        }
    }
    
    private fun checkClientHealth(client: McpSyncClient): Boolean {
        return runCatching {
            // 타임아웃을 짧게 설정해서 더 빠른 실패 감지
            val startTime = System.currentTimeMillis()
            
            val tools = client.listTools()
            val resources = client.listResources()
            
            val duration = System.currentTimeMillis() - startTime
            
            // 응답 시간이 너무 길면 연결에 문제가 있을 가능성
            if (duration > 10_000) { // 10초 이상
                log.warn("Health check took too long for {}: {}ms", client, duration)
                return false
            }
            
            log.debug("Health check passed for {}: tools={}, resources={}, time={}ms", 
                client, tools.tools.size, resources.resources.size, duration)
            true
        }.onFailure { e ->
            log.warn("Health check FAILED for {}: {} ({})", client, e.message, e.javaClass.simpleName)
            // 더 상세한 에러 정보 로깅
            when {
                e.message?.contains("Connection refused") == true -> log.warn("🔌 Server connection refused")
                e.message?.contains("timeout") == true -> log.warn("⏰ Request timeout")
                e.message?.contains("EOF") == true -> log.warn("📡 Connection unexpectedly closed")
                else -> log.warn("🔥 Unknown connection error: {}", e.javaClass.simpleName)
            }
        }.isSuccess
    }
    
    private fun attemptReconnection(client: McpSyncClient) {
        val clientId = client.toString()
        log.info("🔄 Attempting to reconnect MCP client: {}", clientId)
        
        runCatching {
            // SSE 연결의 경우 더 강력한 재연결 방법 필요
            if (clientId.contains("HttpClientSseClientTransport")) {
                log.info("💡 Detected SSE client, using enhanced reconnection strategy")
                attemptSseReconnection(client, clientId)
            } else {
                // 기본 재연결 로직
                attemptStandardReconnection(client, clientId)
            }
            
            // 성공 시 재시도 카운터 리셋
            clientReconnectAttempts.remove(clientId)
            
        }.onFailure { e ->
            log.warn("❌ Failed to reconnect MCP client {}: {}", clientId, e.message)
            // 재연결 실패 시 상태를 명시적으로 false로 설정
            clientHealthStatus[clientId] = false
        }
    }
    
    private fun attemptSseReconnection(client: McpSyncClient, clientId: String) {
        log.info("🔧 Using SSE-specific reconnection strategy for: {}", clientId)
        
        // 1. SSE Transport를 강제로 닫기
        try {
            val transportField = getTransportField(client)
            transportField?.let { field ->
                val transport = field.get(client)
                transport?.let { t ->
                    val closeMethod = t.javaClass.getMethod("close")
                    closeMethod.invoke(t)
                    log.debug("Closed SSE transport for: {}", clientId)
                }
            }
        } catch (e: Exception) {
            log.debug("Could not close SSE transport directly: {}", e.message)
        }
        
        // 2. 클라이언트 상태 리셋
        try {
            client.close()
        } catch (e: Exception) {
            log.debug("Expected error closing client: {}", e.message)
        }
        
        // 3. 더 긴 대기 시간 (SSE 연결은 더 시간이 필요함)
        Thread.sleep(5000)
        
        // 4. 여러 번 재시도
        var retryCount = 0
        val maxRetries = 3
        
        while (retryCount < maxRetries) {
            try {
                retryCount++
                log.debug("SSE reconnection attempt {} of {} for: {}", retryCount, maxRetries, clientId)
                
                client.initialize()
                
                // 연결 검증
                val tools = client.listTools()
                log.info("✅ SSE client reconnected successfully: {} (tools: {})", clientId, tools.tools.size)
                return
                
            } catch (e: Exception) {
                log.debug("SSE reconnection attempt {} failed: {}", retryCount, e.message)
                if (retryCount < maxRetries) {
                    Thread.sleep((2000 * retryCount).toLong()) // 점진적 백오프
                } else {
                    throw e
                }
            }
        }
    }
    
    private fun attemptStandardReconnection(client: McpSyncClient, clientId: String) {
        // 1. 기존 연결 정리
        try {
            client.close()
            log.debug("Closed existing connection for: {}", clientId)
        } catch (e: Exception) {
            log.debug("Error closing connection (expected): {}", e.message)
        }
        
        // 2. 잠시 대기
        Thread.sleep(3000)
        
        // 3. 재초기화 시도
        client.initialize()
        log.info("✅ Successfully reconnected MCP client: {}", clientId)
        
        // 4. 연결 확인
        val tools = client.listTools()
        log.debug("✅ Connection verified for: {} (tools: {})", clientId, tools.tools.size)
    }
    
    private fun getTransportField(client: McpSyncClient): Field? {
        return try {
            val field = client.javaClass.getDeclaredField("transport")
            field.isAccessible = true
            field
        } catch (e: Exception) {
            log.debug("Could not access transport field: {}", e.message)
            null
        }
    }
    
    // 수동으로 모든 클라이언트 재연결 시도
    fun forceReconnectAll() {
        log.info("🔄 Force reconnecting all MCP clients...")
        clients.forEach { client ->
            clientReconnectAttempts.remove(client.toString()) // 강제 재연결 시 대기 시간 무시
            attemptReconnection(client)
        }
    }
    
    // 특정 클라이언트 재연결
    fun forceReconnect(clientId: String) {
        clients.find { it.toString().contains(clientId) }?.let { client ->
            log.info("🔄 Force reconnecting specific MCP client: {}", clientId)
            clientReconnectAttempts.remove(client.toString()) // 강제 재연결 시 대기 시간 무시
            attemptReconnection(client)
        } ?: log.warn("❌ Client not found: {}", clientId)
    }
    
    // 헬스 상태 조회
    fun getHealthStatus(): Map<String, Any> {
        return clients.associate { client ->
            val clientId = client.toString()
            val isHealthy = checkClientHealth(client)
            clientHealthStatus[clientId] = isHealthy
            
            val lastAttempt = clientReconnectAttempts[clientId]
            
            clientId to mapOf(
                "healthy" to isHealthy,
                "lastCheck" to System.currentTimeMillis(),
                "lastReconnectAttempt" to lastAttempt,
                "tools" to runCatching { 
                    client.listTools().tools.map { it.name }
                }.getOrElse { 
                    log.debug("Failed to get tools for status check: {}", clientId)
                    emptyList<String>() 
                },
                "resources" to runCatching {
                    client.listResources().resources.map { it.name }
                }.getOrElse {
                    log.debug("Failed to get resources for status check: {}", clientId)
                    emptyList<String>()
                }
            )
        }
    }
}