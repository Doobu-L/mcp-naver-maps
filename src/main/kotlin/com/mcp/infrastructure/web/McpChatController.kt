package com.mcp.infrastructure.web

import io.modelcontextprotocol.client.McpSyncClient
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CompletableFuture

data class AskReq(val user: String)
data class AskRes(val answer: String)

@RestController
@RequestMapping("/chat")
class McpChatController(
    private val chatModel: ChatModel,
    private val mcpClients: List<McpSyncClient>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 기존 동기 방식 - 이번엔 tools를 명시적으로 활성화
    @PostMapping("/ask")
    fun ask(@RequestBody req: AskReq): AskRes {
        log.info("=== 채팅 요청 시작 ===")
        log.info("사용자 입력: {}", req.user)

        try {
            val sys = """
                You are a Korean assistant that MUST use MCP tools when relevant.
                Available tools:
                - Use 'geocode' for address-to-coordinate conversion.
                - Use 'localSearch' to find places (returns addresses).
                
                IMPORTANT: When a user asks for location information, you MUST call the appropriate tool.
                Think step-by-step and call tools automatically when needed.
            """.trimIndent()

            log.info("시스템 메시지: {}", sys)

            // Create options with function calling enabled
            val options = VertexAiGeminiChatOptions.builder()
                .temperature(0.2)
                .build()

            val prompt = Prompt("$sys\n\n사용자: ${req.user}", options)
            val response = chatModel.call(prompt)
            val out = response.result.output.text

            log.info("AI 응답: {}", out)
            log.info("=== 채팅 요청 완료 ===")

            return AskRes(answer = out ?: "")
        } catch (e: Exception) {
            log.error("채팅 처리 중 오류 발생", e)
            return AskRes(answer = "오류가 발생했습니다: ${e.message}")
        }
    }

    // 새로운 SSE 스트리밍 방식
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamChat(@RequestParam message: String): SseEmitter {
        val emitter = SseEmitter(60000L) // 60초 타임아웃
        
        log.info("=== SSE 스트리밍 요청 시작 ===")
        log.info("사용자 입력: {}", message)

        CompletableFuture.runAsync {
            try {
                val sys = """
                    You are a Korean assistant that MUST use MCP tools when relevant.
                    Available tools:
                    - Use 'geocode' for address-to-coordinate conversion.
                    - Use 'localSearch' to find places (returns addresses).
                    
                    IMPORTANT: When a user asks for location information, you MUST call the appropriate tool.
                    Think step-by-step and call tools automatically when needed.
                """.trimIndent()

                // Create options with function calling enabled
                val options = VertexAiGeminiChatOptions.builder()
                    .temperature(0.2)
                    .build()

                // 스트리밍 호출
                val prompt = Prompt("$sys\n\n사용자: $message", options)
                val stream = chatModel.stream(prompt)

                stream.subscribe(
                    { chatResponse ->
                        val chunk = chatResponse.result.output.text
                        log.debug("스트림 청크: {}", chunk)
                        emitter.send(SseEmitter.event()
                            .name("message")
                            .data(chunk ?: ""))
                    },
                    { error ->
                        log.error("스트리밍 중 오류 발생", error)
                        emitter.send(SseEmitter.event()
                            .name("error")
                            .data("오류가 발생했습니다: ${error.message}"))
                        emitter.complete()
                    },
                    {
                        log.info("=== SSE 스트리밍 완료 ===")
                        emitter.send(SseEmitter.event()
                            .name("done")
                            .data("[DONE]"))
                        emitter.complete()
                    }
                )
            } catch (e: Exception) {
                log.error("스트리밍 시작 중 오류 발생", e)
                emitter.send(SseEmitter.event()
                    .name("error")
                    .data("오류가 발생했습니다: ${e.message}"))
                emitter.complete()
            }
        }

        emitter.onCompletion { log.info("SSE 연결 완료") }
        emitter.onTimeout { log.warn("SSE 타임아웃") }
        emitter.onError { log.error("SSE 오류: {}", it.message) }

        return emitter
    }
}