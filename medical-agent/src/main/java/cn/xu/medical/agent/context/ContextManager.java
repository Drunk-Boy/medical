package cn.xu.medical.agent.context;

import cn.xu.medical.agent.common.entity.AgentMessage;
import cn.xu.medical.agent.common.entity.ContextSnapshot;
import cn.xu.medical.agent.common.mapper.ContextSnapshotMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Five-stage context management pipeline to prevent context collapse.
 *
 * Phase 1: Structural pruning
 * Phase 2: Token budget monitoring
 * Phase 3: Message importance scoring
 * Phase 4: Summary compaction (LLM-powered)
 * Phase 5: Context visualization
 */
@Slf4j
@Component
public class ContextManager {

    private static final int MAX_TOKENS = 128000;
    private static final int WARNING_THRESHOLD_PCT = 70;
    private static final int COMPACT_THRESHOLD_PCT = 85;
    private static final int MAX_TOOL_RESULT_CHARS = 500;
    private static final String USAGE_KEY_PREFIX = "agent:context:usage:";

    private final ChatModel chatModel;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ContextSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;

    @Value("${agent.context.warning-threshold-pct:70}")
    private int warningThreshold;

    @Value("${agent.context.compact-threshold-pct:85}")
    private int compactThreshold;

    public ContextManager(ChatModel chatModel,
                          RedisTemplate<String, Object> redisTemplate,
                          ContextSnapshotMapper snapshotMapper,
                          ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.redisTemplate = redisTemplate;
        this.snapshotMapper = snapshotMapper;
        this.objectMapper = objectMapper;
        this.warningThreshold = WARNING_THRESHOLD_PCT;
        this.compactThreshold = COMPACT_THRESHOLD_PCT;
    }

    /**
     * Run the full pipeline on a list of messages.
     * Returns the (possibly pruned/compacted) message list.
     */
    public List<AgentMessage> process(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        // Phase 1: Structural pruning
        List<AgentMessage> pruned = structuralPrune(messages);

        // Phase 2: Estimate tokens
        int totalTokens = estimateTotalTokens(pruned);
        double usagePct = (double) totalTokens / MAX_TOKENS * 100;

        // Phase 2b: Update Redis for frontend polling
        if (pruned.size() > 0) {
            String sessionId = pruned.getFirst().getSessionId();
            if (sessionId != null) {
                redisTemplate.opsForValue().set(USAGE_KEY_PREFIX + sessionId,
                    Map.of("totalTokens", totalTokens, "maxTokens", MAX_TOKENS, "usagePct", Math.round(usagePct)),
                    Duration.ofMinutes(5));
            }
        }

        // Phase 3 & 4: If over threshold, score and compact
        if (usagePct >= compactThreshold && pruned.size() > 10) {
            log.info("Context: {}% used ({} tokens), triggering compaction", Math.round(usagePct), totalTokens);
            pruned = scoreAndCompact(pruned, totalTokens);

            // Save snapshot
            if (!pruned.isEmpty()) {
                saveSnapshot(pruned.getFirst().getSessionId(), totalTokens, usagePct, true);
            }
        } else if (usagePct >= warningThreshold) {
            log.debug("Context: {}% used ({} tokens), approaching limit", Math.round(usagePct), totalTokens);
            if (!pruned.isEmpty()) {
                saveSnapshot(pruned.getFirst().getSessionId(), totalTokens, usagePct, false);
            }
        }

        return pruned;
    }

    // --- Phase 1: Structural Pruning ---

    private List<AgentMessage> structuralPrune(List<AgentMessage> messages) {
        return messages.stream()
            .map(this::pruneMessage)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    private AgentMessage pruneMessage(AgentMessage msg) {
        if (msg.getContent() == null) {
            return msg;
        }

        // Tool results: truncate to essential info
        if ("tool_result".equals(msg.getRole())) {
            String content = msg.getContent();
            if (content.length() > MAX_TOOL_RESULT_CHARS) {
                msg.setContent(content.substring(0, MAX_TOOL_RESULT_CHARS) + "... (已截断)");
            }
        }

        // Tool calls: keep but trim JSON
        if ("tool_call".equals(msg.getRole()) && msg.getMetadataJson() != null) {
            // Keep metadata but limit size
            if (msg.getMetadataJson().length() > 200) {
                msg.setMetadataJson(msg.getMetadataJson().substring(0, 200));
            }
        }

        return msg;
    }

    // --- Phase 2: Token Estimation ---

    public int estimateTotalTokens(List<AgentMessage> messages) {
        return messages.stream()
            .mapToInt(m -> (m.getContent() != null ? estimateTokens(m.getContent()) : 0)
                + (m.getMetadataJson() != null ? estimateTokens(m.getMetadataJson()) : 0))
            .sum();
    }

    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        int chinese = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                chinese++;
            }
        }
        int other = text.length() - chinese;
        return (int)(chinese * 1.5 + other / 4.0);
    }

    // --- Phase 3: Importance Scoring ---

    private List<AgentMessage> scoreAndCompact(List<AgentMessage> messages, int currentTokens) {
        // Score messages
        List<ScoredMessage> scored = messages.stream()
            .map(m -> new ScoredMessage(m, calculateScore(m)))
            .sorted(Comparator.comparingDouble(ScoredMessage::score))
            .toList();

        // Target: reduce to 60% usage
        int targetTokens = (int)(MAX_TOKENS * 0.6);
        int tokensToRemove = currentTokens - targetTokens;

        // Select lowest-scored messages to compact
        List<AgentMessage> toCompact = new ArrayList<>();
        int accumulated = 0;
        for (ScoredMessage sm : scored) {
            if (accumulated >= tokensToRemove) break;
            int t = sm.message().getTokenCount() != null ? sm.message().getTokenCount()
                : estimateTokens(sm.message().getContent());
            accumulated += t;
            toCompact.add(sm.message());
        }

        if (!toCompact.isEmpty()) {
            // Phase 4: LLM compaction
            String summary = compactMessages(toCompact);
            AgentMessage summaryMsg = new AgentMessage();
            summaryMsg.setRole("summary");
            summaryMsg.setContent(summary);
            summaryMsg.setTokenCount(estimateTokens(summary));

            // Replace compacted messages with summary
            List<AgentMessage> result = new ArrayList<>(messages);
            result.removeAll(toCompact);
            result.add(summaryMsg);

            log.info("Context: compacted {} messages → 1 summary (saved ~{} tokens)", toCompact.size(), accumulated);
            return result;
        }

        return messages;
    }

    private double calculateScore(AgentMessage msg) {
        double base = switch (msg.getRole()) {
            case "user" -> 1.0;
            case "assistant" -> 0.8;
            case "tool_result" -> 0.3;
            case "tool_call" -> 0.4;
            case "system" -> 1.0; // protect system messages
            case "summary" -> 0.6;
            default -> 0.5;
        };

        // Boost: error messages are important
        String content = msg.getContent();
        if (content != null) {
            if (content.contains("Exception") || content.contains("Error") || content.contains("FAILED")) {
                base += 0.3;
            }
            if (content.contains("```")) base += 0.1; // code blocks
        }

        return base;
    }

    private String compactMessages(List<AgentMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("请将以下对话历史压缩为简洁摘要（保留关键信息）：\n\n");
        for (AgentMessage m : messages) {
            sb.append("[").append(m.getRole()).append("]: ")
                .append(m.getContent() != null ? m.getContent().substring(0, Math.min(300, m.getContent().length())) : "")
                .append("\n");
        }

        try {
            return chatModel.call(sb.toString());
        } catch (Exception e) {
            log.warn("Compaction failed: {}", e.getMessage());
            return "(压缩失败)";
        }
    }

    // --- Phase 5: Visualization ---

    public Map<String, Object> getContextUsage(String sessionId) {
        Object cached = redisTemplate.opsForValue().get(USAGE_KEY_PREFIX + sessionId);
        if (cached instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            m.forEach((k, v) -> result.put(k.toString(), v));
            return result;
        }
        return Map.of("totalTokens", 0, "maxTokens", MAX_TOKENS, "usagePct", 0);
    }

    private void saveSnapshot(String sessionId, int totalTokens, double usagePct, boolean compacted) {
        try {
            ContextSnapshot snapshot = ContextSnapshot.builder()
                .sessionId(sessionId)
                .totalTokens(totalTokens)
                .maxTokens(MAX_TOKENS)
                .usagePct(BigDecimal.valueOf(usagePct).setScale(2, RoundingMode.HALF_UP))
                .compacted(compacted)
                .build();
            snapshotMapper.insert(snapshot);
        } catch (Exception ignored) {}
    }

    record ScoredMessage(AgentMessage message, double score) {}
}
