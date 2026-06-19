package cn.xu.medical.agent.context;

import cn.xu.medical.agent.common.dto.AgentEvent;
import cn.xu.medical.agent.common.entity.AgentMessage;
import cn.xu.medical.agent.tool.ToolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core agent loop: receives user messages, orchestrates ChatClient streaming,
 * emits AgentEvent stream for SSE push.
 */
@Slf4j
@Service
public class AgentLoopService {

    private final ChatModel chatModel;
    private final ToolManager toolManager;
    private final SystemPromptBuilder systemPromptBuilder;
    private final AgentSessionService sessionService;
    private final ContextManager contextManager;

    public AgentLoopService(ChatModel chatModel,
                            ToolManager toolManager,
                            SystemPromptBuilder systemPromptBuilder,
                            AgentSessionService sessionService,
                            ContextManager contextManager) {
        this.chatModel = chatModel;
        this.toolManager = toolManager;
        this.systemPromptBuilder = systemPromptBuilder;
        this.sessionService = sessionService;
        this.contextManager = contextManager;
    }

    /**
     * Process a user message and return a Flux of AgentEvents for SSE streaming.
     */
    public Flux<AgentEvent> processMessage(String sessionId, String userMessage) {
        String messageId = "msg_" + UUID.randomUUID().toString().substring(0, 8);

        return Flux.create(sink -> {
            try {
                // 1. Load session
                var session = sessionService.getById(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

                // 2. Save user message
                int seq = sessionService.getNextSeq(sessionId);
                sessionService.saveMessage(sessionId, "user", userMessage, null, seq);

                // 3. Context management — five-stage pipeline
                var history = sessionService.loadRecentMessages(sessionId, 50);
                contextManager.process(history);

                // 4. Build system prompt
                String systemPrompt = systemPromptBuilder.build();

                // 5. Build ChatClient
                ChatClient client = ChatClient.builder(chatModel)
                    .defaultSystem(systemPrompt)
                    .defaultTools(toolManager.getToolObjects())
                    .defaultAdvisors(new SimpleLoggerAdvisor())
                    .build();

                // 6. Emit thinking event
                sink.next(AgentEvent.builder()
                    .type(AgentEvent.EventType.THINKING)
                    .messageId(messageId)
                    .content("started")
                    .build());

                // 7. Stream response
                StringBuilder fullResponse = new StringBuilder();
                AtomicInteger toolCallCount = new AtomicInteger(0);

                client.prompt()
                    .user(userMessage)
                    .advisors(a -> a.param("chat_memory_conversation_id", sessionId))
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        fullResponse.append(chunk);
                        sink.next(AgentEvent.textChunk(messageId, chunk));
                    })
                    .doOnComplete(() -> {
                        // Save assistant message
                        sessionService.saveMessage(sessionId, "assistant",
                            fullResponse.toString(), null,
                            sessionService.getNextSeq(sessionId));

                        sink.next(AgentEvent.done(messageId,
                            contextManager.estimateTotalTokens(history), toolCallCount.get()));
                        sink.complete();
                    })
                    .doOnError(error -> {
                        log.error("Agent loop error in session {}", sessionId, error);
                        sink.next(AgentEvent.error(messageId, "LOOP_ERROR", error.getMessage()));
                        sink.complete();
                    })
                    .subscribe();

            } catch (Exception e) {
                log.error("Failed to process message for session {}", sessionId, e);
                sink.next(AgentEvent.error(messageId, "PROCESS_ERROR", e.getMessage()));
                sink.complete();
            }
        });
    }
}
