# MCP-NAVER-MAPS

## 1.Architecture
```
[사용자/앱] → [Spring Boot (Spring AI)]
│
├─(MCP Client, STDIO)──▶  [MCP 서버 (naver-maps)]
│                           ├─ geocode()
│                           └─ localSearch()
│
└─(LLM, Anthropic Claude)  ← Tool Calling로 MCP 도구 사용
```

