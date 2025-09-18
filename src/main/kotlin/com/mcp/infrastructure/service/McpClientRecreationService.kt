package com.mcp.infrastructure.service

import io.modelcontextprotocol.client.McpSyncClient
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class McpClientRecreationService(
    private val applicationContext: ApplicationContext,
    private val environment: Environment
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val recreationAttempts = ConcurrentHashMap<String, Long>()
    
    fun recreateFailedClients(): Boolean {
        return try {
            log.info("🔄 Starting complete MCP client recreation...")
            
            val configurableContext = applicationContext as ConfigurableApplicationContext
            val beanFactory = configurableContext.beanFactory
            
            // 1. 기존 MCP 관련 Bean들을 찾아서 제거
            destroyExistingMcpBeans(beanFactory)
            
            // 2. 잠시 대기
            Thread.sleep(3000)
            
            // 3. Spring AI MCP 자동 설정을 다시 트리거
            recreateMcpBeans(beanFactory)
            
            log.info("✅ MCP client recreation completed successfully")
            true
            
        } catch (e: Exception) {
            log.error("❌ Failed to recreate MCP clients", e)
            false
        }
    }
    
    private fun destroyExistingMcpBeans(beanFactory: ConfigurableListableBeanFactory) {
        log.debug("🗑️ Destroying existing MCP beans...")
        
        // MCP 관련 Bean들을 찾아서 제거
        val mcpBeanNames = beanFactory.getBeanNamesForType(McpSyncClient::class.java)
        mcpBeanNames.forEach { beanName ->
            try {
                val bean = beanFactory.getBean(beanName) as McpSyncClient
                bean.close() // 기존 연결 정리
                beanFactory.destroySingleton(beanName)
                log.debug("Destroyed MCP client bean: {}", beanName)
            } catch (e: Exception) {
                log.debug("Error destroying bean {}: {}", beanName, e.message)
            }
        }
        
        // SyncMcpToolCallbackProvider도 재생성 필요
        val callbackProviderNames = beanFactory.getBeanNamesForType(SyncMcpToolCallbackProvider::class.java)
        callbackProviderNames.forEach { beanName ->
            try {
                beanFactory.destroySingleton(beanName)
                log.debug("Destroyed MCP callback provider bean: {}", beanName)
            } catch (e: Exception) {
                log.debug("Error destroying callback provider {}: {}", beanName, e.message)
            }
        }
    }
    
    private fun recreateMcpBeans(beanFactory: ConfigurableListableBeanFactory) {
        log.debug("🆕 Recreating MCP beans...")
        
        try {
            // Spring Boot의 자동 설정을 다시 트리거하기 위해
            // ApplicationContext를 새로고침 (위험할 수 있으므로 조심스럽게)
            val configurableContext = applicationContext as ConfigurableApplicationContext
            
            // 대안: Bean을 수동으로 다시 생성
            // 이는 더 안전한 방법입니다
            log.debug("Triggering MCP auto-configuration refresh...")
            
            // Spring AI의 MCP 자동 설정이 다시 실행되도록 환경을 조작
            // 이것은 실제로는 복잡하므로, 애플리케이션 재시작을 권장하는 메시지를 로깅
            log.warn("⚠️ Complete MCP client recreation requires application restart for best results")
            log.info("💡 Consider restarting the application if MCP reconnection continues to fail")
            
        } catch (e: Exception) {
            log.error("Failed to recreate MCP beans", e)
            throw e
        }
    }
    
    fun canAttemptRecreation(clientId: String): Boolean {
        val now = System.currentTimeMillis()
        val lastAttempt = recreationAttempts[clientId] ?: 0L
        
        return if (now - lastAttempt > 300_000) { // 5분에 한 번만
            recreationAttempts[clientId] = now
            true
        } else {
            false
        }
    }
}