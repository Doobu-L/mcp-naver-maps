# CLAUDE.md

This file provides comprehensive guidance for Claude Code instances working with this MCP Naver Maps Chat project.

## 프로젝트 개요
Spring Boot + Spring AI 기반 AI 채팅 서비스로, MCP(Model Context Protocol)를 통해 네이버 지도 API와 연동하여 위치 기반 질의응답을 제공합니다.

## Build and Development Commands

### Running the Application
```bash
./gradlew bootRun
```
애플리케이션은 포트 9090에서 시작됩니다. 주요 접근점:
- 채팅 UI: http://localhost:9090/sse-test.html
- API 문서: http://localhost:9090/swagger-ui.html
- MCP 디버그 도구: http://localhost:9090/mcp-debug/tools

### Testing and Building
```bash
./gradlew test                 # 테스트 실행
./gradlew build               # 프로젝트 빌드
./gradlew compileKotlin       # Kotlin 소스만 컴파일
```

## Architecture Overview

### 전체 구조
```
[사용자/웹앱] → [Spring Boot + Spring AI]
                     │
                     ├─(MCP Client)──▶  [MCP 서버 (naver-maps)]
                     │                     ├─ geocode() - 주소→좌표 변환
                     │                     └─ localSearch() - 장소 검색
                     │
                     └─(Vertex AI Gemini)  ← Tool Calling으로 MCP 도구 사용
```

### 핵심 컴포넌트

**MCP Client Configuration** (`src/main/kotlin/com/mcp/infrastructure/config/ChatClientConfig.kt`):
- `VertexAiGeminiChatModel`에 `SyncMcpToolCallbackProvider` 주입
- 스프레드 연산자로 도구 콜백 등록: `*syncMcpToolCallbackProvider.toolCallbacks`
- Vertex AI가 대화 중 자동으로 MCP 도구 호출 가능

**MCP Server Connections** (`src/main/resources/application.yml`):
```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        type: SYNC
        sse:
          connections:
            external-mcp:
              url: http://localhost:8080
              sse-endpoint: /sse
```
- **SSE Transport**: 네이버 지도 MCP 서버 (localhost:8080/sse)
- Spring AI 자동 설정으로 관리

**Dual Chat Interfaces** (`src/main/kotlin/com/mcp/infrastructure/web/McpChatController.kt`):
- `POST /chat/ask`: 동기식 채팅
- `GET /chat/stream`: SSE 스트리밍 채팅
- 두 엔드포인트 모두 Vertex AI가 필요시 자동으로 MCP 도구 호출

**Debug Controller** (`src/main/kotlin/com/mcp/infrastructure/web/McpDebugController.kt`):
- MCP 클라이언트 및 도구 상태 디버깅
- `/mcp-debug/tools`, `/mcp-debug/clients` 엔드포인트 제공

### MCP Tool Integration Flow
1. 사용자가 위치 관련 질의 ("운정역을 검색해줘")
2. Vertex AI Gemini가 쿼리 분석 후 `localSearch` 도구 필요성 판단
3. Spring AI MCP 클라이언트가 외부 naver-maps 서버 호출
4. 도구 결과가 Gemini 응답에 자동 통합
5. AI 추론과 실제 위치 데이터가 결합된 최종 답변 제공

## Environment Requirements

**필수 환경 변수**:
- `GOOGLE_CLOUD_PROJECT_ID`: Google Cloud 프로젝트 ID
- `GOOGLE_CLOUD_LOCATION`: Vertex AI 지역 (기본값: us-central1)

**외부 의존성**:
- localhost:8080에서 실행 중인 naver-maps MCP 서버
- Vertex AI API가 활성화된 Google Cloud 프로젝트
- JDK 21+

## 기술 스택
- **Backend**: Spring Boot 3.5, Kotlin 1.9.25
- **AI**: Google Vertex AI Gemini 2.0 Flash
- **Integration**: MCP (Model Context Protocol), Spring AI 1.0.0
- **Frontend**: HTML5, JavaScript, Server-Sent Events
- **Build**: Gradle with Kotlin DSL

## Key Files and Locations

### Core Application Files
- `src/main/kotlin/com/mcp/McpClientApplication.kt`: 메인 애플리케이션 클래스
- `src/main/kotlin/com/mcp/infrastructure/config/ChatClientConfig.kt`: AI 모델 및 MCP 설정
- `src/main/kotlin/com/mcp/infrastructure/web/McpChatController.kt`: 채팅 API 엔드포인트
- `src/main/kotlin/com/mcp/infrastructure/web/McpDebugController.kt`: MCP 디버깅 엔드포인트

### Configuration Files
- `src/main/resources/application.yml`: 핵심 설정 (MCP, Vertex AI, 서버)
- `build.gradle.kts`: 프로젝트 의존성 및 빌드 설정
- `src/main/resources/static/sse-test.html`: 웹 채팅 인터페이스

## Debugging MCP Integration

### Debug Endpoints
- `GET /mcp-debug/tools`: 연결된 서버의 모든 MCP 도구 목록
- `GET /mcp-debug/clients`: 연결된 MCP 클라이언트 인스턴스 목록
- `GET /mcp-debug/tools-detailed`: 상세한 도구 스키마 및 설명

### 로깅 설정
application.yml에서 DEBUG 레벨 로깅 활성화:
```yaml
logging:
  level:
    org.springframework.ai.mcp: DEBUG
    org.springframework.ai.chat: DEBUG
    org.springframework.ai.vertexai: DEBUG
    com.mcp: DEBUG
```

## 알려진 이슈 및 제한사항

### 1. MCP Tool Execution Problem
**문제**: AI가 도구 사용을 선언하지만 실제로는 실행하지 않음
- **증상**: "알겠습니다. 운정역을 검색하기 위해 'localSearch' 도구를 사용하겠습니다." 이후 타임아웃
- **원인**: Spring AI 1.0.0의 MCP 통합 제한사항으로 추정
- **디버깅**: `/mcp-debug/tools` 엔드포인트로 도구 연결 상태 확인

### 2. SSE vs Streamable HTTP
- 현재 SSE 방식 사용 중 (application.yml에서 설정)
- Streamable HTTP는 Spring AI 1.0.0에서 공식 지원하지 않음
- SSE가 안정적이고 권장되는 방식

## 개발 가이드라인

### 코드 컨벤션
- Kotlin 표준 코드 스타일 준수
- 생성자 주입 방식 사용 (field injection 피하기)
- SLF4J 로깅 사용
- RESTful API 설계 원칙 준수

### MCP 연동 패턴
1. `SyncMcpToolCallbackProvider`가 application.yml에서 MCP 서버 자동 발견
2. 도구 콜백이 `VertexAiGeminiChatModel` 기본 옵션에 주입
3. Vertex AI가 대화 컨텍스트 기반으로 자동 도구 호출
4. 수동 도구 호출 코드 불필요 - Spring AI가 MCP 프로토콜 통신 처리

## Testing and Verification

### 기본 테스트 시나리오
1. 애플리케이션 실행: `./gradlew bootRun`
2. 웹 브라우저에서 http://localhost:9090/sse-test.html 접속
3. 채팅창에서 위치 관련 질문 입력:
   - "운정역을 검색해줘"
   - "강남역 근처 카페 찾아줘"
   - "서울시청의 좌표를 알려줘"

### MCP 연결 확인
- `/mcp-debug/tools` 엔드포인트에서 사용 가능한 도구 확인
- naver-maps 서버가 localhost:8080에서 정상 동작하는지 확인
- 로그에서 MCP 연결 상태 모니터링

## 향후 개선 방향
1. MCP 도구 실행 문제 해결 (Spring AI 버전 업그레이드 고려)
2. 추가 MCP 서버 연동 (파일시스템 등)
3. 에러 핸들링 및 사용자 경험 개선
4. 성능 최적화 및 모니터링 강화