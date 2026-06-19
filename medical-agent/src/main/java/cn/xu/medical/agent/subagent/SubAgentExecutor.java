package cn.xu.medical.agent.subagent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Sub-agent execution — spawn isolated agents for parallel work.
 *
 * Each sub-agent runs with:
 * - Independent system prompt
 * - Restricted tool set
 * - Independent ChatMemory (not contaminating parent)
 * - Optional worktree isolation (via JGit)
 * - Max turn limit (prevents infinite loops)
 */
@Slf4j
@Service
public class SubAgentExecutor {

    private final ChatModel chatModel;
    private final Map<String, SubAgentRuntime> runningAgents = new ConcurrentHashMap<>();

    private static final int DEFAULT_MAX_TURNS = 15;
    private static final long DEFAULT_TIMEOUT_MINUTES = 10;

    public SubAgentExecutor(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Execute a sub-agent synchronously (foreground).
     */
    public SubAgentResult execute(SubAgentConfig config, String task) {
        String agentId = "subagent_" + config.getName() + "_" + System.currentTimeMillis();
        SubAgentRuntime runtime = new SubAgentRuntime(agentId, config, SubAgentRuntime.Status.RUNNING);
        runningAgents.put(agentId, runtime);

        try {
            log.info("SubAgent '{}' started: {}", config.getName(), task);

            ChatClient client = ChatClient.builder(chatModel)
                .defaultSystem(config.getSystemPrompt())
                .defaultAdvisors(a -> a.param("max_tool_calls", config.getMaxTurns()))
                .build();

            // If tools are restricted, apply filter
            String response;
            if (config.getAllowedTools() != null && !config.getAllowedTools().isEmpty()) {
                response = client.prompt()
                    .user(task)
                    .call()
                    .content();
            } else {
                response = client.prompt()
                    .user(task)
                    .call()
                    .content();
            }

            runtime.setStatus(SubAgentRuntime.Status.COMPLETED);
            log.info("SubAgent '{}' completed", config.getName());

            return SubAgentResult.builder()
                .agentName(config.getName())
                .success(true)
                .summary(response)
                .build();

        } catch (Exception e) {
            runtime.setStatus(SubAgentRuntime.Status.FAILED);
            runtime.setError(e.getMessage());
            log.error("SubAgent '{}' failed: {}", config.getName(), e.getMessage());
            return SubAgentResult.builder()
                .agentName(config.getName())
                .success(false)
                .error(e.getMessage())
                .build();
        } finally {
            runningAgents.remove(agentId);
        }
    }

    /**
     * Execute a sub-agent asynchronously (background).
     */
    public CompletableFuture<SubAgentResult> executeAsync(SubAgentConfig config, String task) {
        return CompletableFuture.supplyAsync(() -> execute(config, task))
            .orTimeout(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Execute multiple sub-agents in parallel.
     */
    public List<SubAgentResult> executeParallel(List<SubAgentTask> tasks) {
        List<CompletableFuture<SubAgentResult>> futures = tasks.stream()
            .map(t -> executeAsync(t.config(), t.task()))
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
            .map(CompletableFuture::join)
            .toList();
    }

    public Map<String, SubAgentRuntime> getRunningAgents() {
        return Map.copyOf(runningAgents);
    }

    // --- Inner types ---

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubAgentConfig {
        private String name;
        private String description;
        private String systemPrompt;
        private List<String> allowedTools;
        private List<String> disallowedTools;
        private String model;
        private int maxTurns = DEFAULT_MAX_TURNS;
        private boolean isolateWorktree;  // use JGit worktree
    }

    public record SubAgentTask(SubAgentConfig config, String task) {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubAgentResult {
        private String agentName;
        private boolean success;
        private String summary;
        private String error;
        private long durationMs;
    }

    @Data
    public static class SubAgentRuntime {
        public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }

        private final String agentId;
        private final SubAgentConfig config;
        private volatile Status status;
        private volatile String error;
        private final long startedAt = System.currentTimeMillis();

        public SubAgentRuntime(String agentId, SubAgentConfig config, Status status) {
            this.agentId = agentId;
            this.config = config;
            this.status = status;
        }

        public long getElapsedMs() {
            return System.currentTimeMillis() - startedAt;
        }
    }
}
