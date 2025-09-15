package com.mcp.infrastructure.config

import com.google.cloud.vertexai.VertexAI
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration


@Configuration
class ChatClientConfig {

    @Bean
    fun vertexAI(): VertexAI = VertexAI()

    @Bean
    fun vertexChatModel(
        vertexAI: VertexAI,
        syncMcpToolCallbackProvider: SyncMcpToolCallbackProvider
    ): VertexAiGeminiChatModel =
        VertexAiGeminiChatModel.builder()
            .vertexAI(vertexAI)
            .defaultOptions(
                VertexAiGeminiChatOptions.builder()
                    .model(VertexAiGeminiChatModel.ChatModel.GEMINI_2_0_FLASH)
                    .temperature(0.2)
                    .toolCallbacks(*syncMcpToolCallbackProvider.toolCallbacks)
                    .build()
            )
            .build()

}
