# Agent 核心循环设计

## 状态机

```
                  ┌─────────────┐
                  │   IDLE      │
                  └──────┬──────┘
                         │ 用户发送消息
                         ▼
                  ┌─────────────┐
                  │  THINKING   │ ← DeepSeek 流式推理中
                  └──────┬──────┘
                         │
              ┌──────────┼──────────┐
              │          │          │
              ▼          ▼          ▼
       ┌──────────┐ ┌────────┐ ┌────────┐
       │TEXT_CHUNK│ │TOOL_   │ │ ERROR  │
       │ (流式)    │ │CALL    │ │        │
       └────┬─────┘ └───┬────┘ └───┬────┘
            │           │          │
            │           ▼          │
            │    ┌────────────┐    │
            │    │ EXECUTING  │    │
            │    │ (工具执行)  │    │
            │    └─────┬──────┘    │
            │          │           │
            │          ▼           │
            │    ┌────────────┐    │
            │    │TOOL_RESULT │    │
            │    │ (返回模型)  │    │
            │    └─────┬──────┘    │
            │          │           │
            │          └───循环────┘
            │
            ▼
     ┌─────────────┐
     │   DONE      │
     └─────────────┘
```

## SSE 事件类型契约

### 事件流格式

```
Content-Type: text/event-stream

event: thinking
data: {"messageId":"msg_001","status":"started"}

event: text_chunk
data: {"messageId":"msg_001","content":"我","index":0}

event: text_chunk
data: {"messageId":"msg_001","content":"来","index":1}

event: tool_call_start
data: {"messageId":"msg_002","toolName":"GrepTool","params":{"pattern":"error","path":"src/"},"callId":"call_001"}

event: tool_call_result
data: {"messageId":"msg_003","callId":"call_001","success":true,"output":"src/main/App.java:42: error message...","durationMs":234}

event: text_chunk
data: {"messageId":"msg_001","content":"分析","index":2}

event: done
data: {"messageId":"msg_001","totalTokens":4523,"toolCalls":1}
```

### 完整事件类型

| Event | 说明 | 关键字段 |
|---|---|---|
| `thinking` | 模型开始思考 | `status`: started/stopped |
| `text_chunk` | 流式文本块 | `content`, `index` |
| `tool_call_start` | 工具调用开始 | `toolName`, `params`, `callId` |
| `tool_call_progress` | 工具执行进度（长任务） | `callId`, `progress`(0-100) |
| `tool_call_result` | 工具执行结果 | `callId`, `success`, `output`, `durationMs` |
| `permission_required` | 需要用户确认 | `callId`, `toolName`, `command`, `risk` |
| `error` | 错误 | `code`, `message` |
| `done` | 本轮完成 | `totalTokens`, `toolCalls` |

## AgentLoopService 核心实现

```java
@Service
public class AgentLoopService {

    private final DeepSeekChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ContextManager contextManager;
    private final PermissionChain permissionChain;
    private final SseEmitterService sseService;

    public Flux<AgentEvent> processMessage(String sessionId, String userMessage) {
        return Flux.create(sink -> {
            // 1. 加载会话上下文
            AgentSession session = sessionService.getOrCreate(sessionId);
            List<Message> history = sessionService.loadRecentMessages(sessionId, 50);

            // 2. 上下文管理 - 五阶段管线
            history = contextManager.process(history);

            // 3. 构建系统提示
            String systemPrompt = systemPromptBuilder.build(session);

            // 4. 构建工具列表(含 MCP 懒加载工具名)
            List<ToolCallback> tools = toolRegistry.getAllToolCallbacks();

            // 5. 调用 ChatClient
            ChatClient client = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultTools(tools)
                .defaultAdvisors(new ToolCallingAdvisor()) // 自动工具调用循环
                .build();

            // 6. 流式输出
            client.prompt()
                .user(userMessage)
                .advisors(advisor -> advisor.param("chat_memory_conversation_id", sessionId))
                .stream()
                .content()
                .doOnNext(chunk -> sink.next(AgentEvent.textChunk(chunk)))
                .doOnComplete(() -> {
                    sink.next(AgentEvent.done());
                    sink.complete();
                })
                .doOnError(error -> {
                    sink.next(AgentEvent.error(error.getMessage()));
                    sink.complete();
                })
                .subscribe();
        });
    }
}
```

## 前端 SSE 消费

```typescript
// useAgentStore.ts
const eventSource = new EventSource(`/api/agent/${sessionId}/stream?message=${encodeURIComponent(input)}`);

eventSource.addEventListener('text_chunk', (e) => {
  const data = JSON.parse(e.data);
  appendToCurrentMessage(data.content);
});

eventSource.addEventListener('tool_call_start', (e) => {
  const data = JSON.parse(e.data);
  addToolCallCard(data);
});

eventSource.addEventListener('tool_call_result', (e) => {
  const data = JSON.parse(e.data);
  updateToolCallCard(data.callId, data);
});

eventSource.addEventListener('permission_required', (e) => {
  const data = JSON.parse(e.data);
  showPermissionDialog(data);
});

eventSource.addEventListener('done', (e) => {
  const data = JSON.parse(e.data);
  finalizeMessage(data);
  eventSource.close();
});
```

## 会话管理

### API 端点

| Method | Path | 说明 |
|---|---|---|
| `POST` | `/api/agent/session` | 创建新会话 |
| `GET` | `/api/agent/sessions` | 列出所有会话 |
| `GET` | `/api/agent/{sessionId}/messages` | 获取会话消息历史 |
| `POST` | `/api/agent/{sessionId}/send` | 发送消息(同步，返回完整响应) |
| `GET` | `/api/agent/{sessionId}/stream` | 发送消息(SSE 流式) |
| `DELETE` | `/api/agent/{sessionId}` | 删除会话 |
| `POST` | `/api/agent/{sessionId}/compact` | 手动触发上下文压缩 |
| `GET` | `/api/agent/{sessionId}/context` | 获取上下文使用情况 |

### ChatMemory 实现

使用 Spring AI 的 `JdbcChatMemory`（基于 MySQL），配合 Redis 缓存热点会话：

```java
@Bean
public ChatMemory chatMemory(DataSource dataSource, RedisTemplate<String, Object> redis) {
    // 先查 Redis，未命中再查 DB
    return new CachedChatMemory(
        new JdbcChatMemory(dataSource),
        redis,
        Duration.ofMinutes(30)
    );
}
```
