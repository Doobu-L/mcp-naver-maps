package com.mcp.infrastructure.config

import com.google.cloud.vertexai.VertexAI
import org.springframework.ai.chat.client.ChatClient
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
    fun vertexChatModel(vertexAI: VertexAI): VertexAiGeminiChatModel =
        VertexAiGeminiChatModel.builder()
            .vertexAI(vertexAI)
            .defaultOptions(
                VertexAiGeminiChatOptions.builder()
                    .model(VertexAiGeminiChatModel.ChatModel.GEMINI_2_0_FLASH)
                    .temperature(0.2) // 안정성 향상
                    .build()
            )
            .build()

    @Bean
    fun chatClient(
        vertexChatModel: VertexAiGeminiChatModel,
        mcpTools: SyncMcpToolCallbackProvider
    ): ChatClient {
        return ChatClient.builder(vertexChatModel)
            .defaultToolCallbacks(*mcpTools.toolCallbacks)
            .defaultSystem(
                """
                You are a Korean assistant.
                When calling MCP tools, STRICTLY follow the tool parameter JSON schema:
                - Use correct JSON types (number vs string vs boolean, etc).
                - Provide all required fields; if you cannot, DO NOT call the tool.
                - For integer fields, use JSON number (e.g., 123), not string ("123").
                - Do not guess or coerce types; adhere to the schema exactly.
                """.trimIndent()
            )
            .build()
    }
}
