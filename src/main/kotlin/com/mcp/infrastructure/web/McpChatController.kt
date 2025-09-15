package com.mcp.infrastructure.web

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CompletableFuture

data class AskReq(val user: String)
data class AskRes(val answer: String)

@RestController
@RequestMapping("/chat")
class McpChatController(
    private val chat: ChatClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 기존 동기 방식 유지
    @PostMapping("/ask")
    fun ask(@RequestBody req: AskReq): AskRes {
        log.info("=== 채팅 요청 시작 ===")
        log.info("사용자 입력: {}", req.user)

        try {
            val sys = """
                You are a Korean assistant. Use MCP tools if relevant.
                - Use 'geocode' for address-to-coordinate.
                - Use 'localSearch' to find places (returns addresses).
                Think step-by-step and call tools when needed.
            """.trimIndent()

            log.info("시스템 메시지: {}", sys)

            val out = chat.prompt()
                .system(sys)
                .user(req.user)
                .call()
                .content()

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
                    You are a Korean assistant. Use MCP tools if relevant.
                    - Use 'geocode' for address-to-coordinate.
                    - Use 'localSearch' to find places (returns addresses).
                    Think step-by-step and call tools when needed.
                """.trimIndent()

                // 스트리밍 호출
                val stream = chat.prompt()
                    .system(sys)
                    .user(message)
                    .stream()
                    .content()

                stream.subscribe(
                    { chunk ->
                        log.debug("스트림 청크: {}", chunk)
                        emitter.send(SseEmitter.event()
                            .name("message")
                            .data(chunk))
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
