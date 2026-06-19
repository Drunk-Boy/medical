package cn.xu.medical.agent.speculation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speculative execution: predict user intent and pre-execute tasks.
 *
 * When user starts typing, the system predicts the intent and
 * runs background tasks (e.g., grep for errors, read relevant files).
 * When user sends the message, cached results are injected immediately.
 */
@Slf4j
@Service
public class SpeculationService {

    private static final int MAX_CACHE_SIZE = 200;
    private static final int DEBOUNCE_MS = 500;

    private final ChatModel chatModel;
    private final Cache<String, SpeculationResult> cache;

    /** Active speculation tasks, keyed by sessionId */
    private final Map<String, CompletableFuture<SpeculationResult>> activeTasks = new ConcurrentHashMap<>();

    private final int debounceMs;
    private final Duration cacheTtl;

    /** Possible intent labels */
    private static final String[] INTENTS = {
        "FIX_BUG", "ADD_FEATURE", "REFACTOR", "EXPLAIN", "SEARCH",
        "TEST", "DEPLOY", "REVIEW", "DOCS", "OTHER"
    };

    public SpeculationService(ChatModel chatModel,
                              @Value("${agent.speculation.debounce-ms:500}") int debounceMs,
                              @Value("${agent.speculation.cache-ttl-seconds:30}") int cacheTtlSeconds) {
        this.chatModel = chatModel;
        this.debounceMs = debounceMs;
        this.cacheTtl = Duration.ofSeconds(cacheTtlSeconds);
        this.cache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .expireAfterWrite(this.cacheTtl)
            .build();
    }

    /**
     * Called on each keystroke (debounced). Predicts what the user is about to ask.
     */
    public String predictIntent(String partialInput, java.util.List<String> recentHistory) {
        if (partialInput == null || partialInput.length() < 3) return null;

        try {
            String prompt = buildIntentPrompt(partialInput, recentHistory);
            String response = chatModel.call(prompt).trim().toUpperCase();

            for (String intent : INTENTS) {
                if (response.contains(intent)) return intent;
            }
            return "OTHER";
        } catch (Exception e) {
            log.debug("Intent prediction failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Start background speculation task based on predicted intent.
     */
    @Async
    public CompletableFuture<SpeculationResult> speculate(String sessionId, String intent, String partialInput) {
        // Cancel any existing speculation for this session
        cancelSpeculation(sessionId);

        CompletableFuture<SpeculationResult> future = CompletableFuture.supplyAsync(() -> {
            log.debug("Speculating: intent={} for session={}", intent, sessionId);
            // In production: run grep, file reads, etc. based on intent template
            String result = "预加载完成: 意图=" + intent + ", 输入=" + partialInput;

            SpeculationResult sr = new SpeculationResult(intent, result, System.currentTimeMillis());
            cache.put(cacheKey(sessionId, intent), sr);
            return sr;
        });

        activeTasks.put(sessionId, future);
        return future;
    }

    /**
     * Check if speculation result is available.
     */
    public SpeculationResult getCachedResult(String sessionId, String intent) {
        return cache.getIfPresent(cacheKey(sessionId, intent));
    }

    /**
     * Cancel active speculation for a session.
     */
    public void cancelSpeculation(String sessionId) {
        CompletableFuture<SpeculationResult> task = activeTasks.remove(sessionId);
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
    }

    private String cacheKey(String sessionId, String intent) {
        return sessionId + ":" + (intent != null ? intent : "OTHER");
    }

    private String buildIntentPrompt(String partialInput, java.util.List<String> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("根据用户正在输入的内容，预测最可能的意图。仅回复一个标签：\n");
        sb.append("FIX_BUG（修bug）, ADD_FEATURE（加功能）, REFACTOR（重构）, EXPLAIN（解释代码）,\n");
        sb.append("SEARCH（搜索）, TEST（测试）, DEPLOY（部署）, REVIEW（Review）, DOCS（文档）, OTHER（其他）\n\n");
        sb.append("用户输入: ").append(partialInput).append("\n");
        return sb.toString();
    }

    public record SpeculationResult(String intent, String data, long timestamp) {}
}
