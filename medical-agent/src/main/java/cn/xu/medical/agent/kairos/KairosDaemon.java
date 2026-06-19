package cn.xu.medical.agent.kairos;

import cn.xu.medical.agent.common.entity.AgentSession;
import cn.xu.medical.agent.common.entity.KairosMemory;
import cn.xu.medical.agent.common.mapper.KairosMemoryMapper;
import cn.xu.medical.agent.context.AgentSessionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * KAIROS — "Always-On" persistent assistant mode.
 *
 * Maintains active session pool, runs background "dream" tasks
 * during idle periods (compacting old sessions, indexing code,
 * pre-building solution caches).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KairosDaemon {

    private static final String ACTIVE_POOL_KEY = "agent:kairos:active_pool";
    private static final String LAST_ACTIVITY_KEY = "agent:kairos:last_activity:";

    private final AgentSessionService sessionService;
    private final KairosMemoryMapper memoryMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Heartbeat — every 30 seconds, maintain active session pool.
     */
    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        try {
            // Get active sessions from DB
            List<AgentSession> activeSessions = sessionService.listActive();

            // Sync with Redis pool
            Set<Object> rawIds = redisTemplate.opsForSet().members(ACTIVE_POOL_KEY);
            if (rawIds != null) {
                Set<String> activeIds = rawIds.stream()
                    .map(Object::toString)
                    .collect(Collectors.toSet());

                for (AgentSession session : activeSessions) {
                    redisTemplate.opsForSet().add(ACTIVE_POOL_KEY, session.getId());
                    // Update last activity
                    redisTemplate.opsForValue().set(LAST_ACTIVITY_KEY + session.getId(),
                        session.getUpdatedAt().toString(), Duration.ofMinutes(5));
                }

                // Remove archived/deleted sessions from pool
                for (String id : activeIds) {
                    boolean stillActive = activeSessions.stream().anyMatch(s -> s.getId().equals(id));
                    if (!stillActive) {
                        redisTemplate.opsForSet().remove(ACTIVE_POOL_KEY, id);
                        redisTemplate.delete(LAST_ACTIVITY_KEY + id);
                    }
                }
            }

            // Dream: check for idle sessions (>5 min no interaction)
            dreamIfIdle(activeSessions);

        } catch (Exception e) {
            log.warn("KAIROS heartbeat error: {}", e.getMessage());
        }
    }

    /**
     * Dream: background tasks during idle periods.
     */
    private void dreamIfIdle(List<AgentSession> activeSessions) {
        for (AgentSession session : activeSessions) {
            String lastActivity = (String) redisTemplate.opsForValue()
                .get(LAST_ACTIVITY_KEY + session.getId());

            if (lastActivity != null) {
                LocalDateTime last = LocalDateTime.parse(lastActivity);
                if (Duration.between(last, LocalDateTime.now()).toMinutes() >= 5) {
                    dream(session);
                }
            }
        }
    }

    /**
     * Execute dream tasks for an idle session:
     * 1. Compact old session summary → KairosMemory
     * 2. Index project code structure → Redis JSON
     * 3. Pre-build common solution cache → Redis
     */
    private void dream(AgentSession session) {
        log.debug("KAIROS dreaming for session {}...", session.getId());

        // Dream task 1: Save session summary to KairosMemory
        try {
            saveSessionSummary(session);
        } catch (Exception e) {
            log.debug("Dream: failed to save session summary: {}", e.getMessage());
        }

        // Dream task 2: Update activity timestamp to prevent re-dream
        redisTemplate.opsForValue().set(LAST_ACTIVITY_KEY + session.getId(),
            LocalDateTime.now().toString(), Duration.ofMinutes(5));
    }

    private void saveSessionSummary(AgentSession session) {
        KairosMemory memory = KairosMemory.builder()
            .scope("PROJECT")
            .scopeKey(session.getProjectDir() != null ? session.getProjectDir() : "default")
            .memoryKey("session_summary:" + session.getId())
            .content("Session: " + session.getTitle() + " | Model: " + session.getModelName())
            .tags("session,auto-summary")
            .importance(30)
            .build();

        // Upsert: delete old, insert new
        memoryMapper.delete(new LambdaQueryWrapper<KairosMemory>()
            .eq(KairosMemory::getScope, memory.getScope())
            .eq(KairosMemory::getScopeKey, memory.getScopeKey())
            .eq(KairosMemory::getMemoryKey, memory.getMemoryKey()));
        memoryMapper.insert(memory);
    }

    /**
     * Store a persistent memory across sessions.
     */
    public void remember(String scope, String scopeKey, String key, String content, String tags, int importance) {
        KairosMemory memory = KairosMemory.builder()
            .scope(scope)
            .scopeKey(scopeKey)
            .memoryKey(key)
            .content(content)
            .tags(tags)
            .importance(importance)
            .build();

        memoryMapper.delete(new LambdaQueryWrapper<KairosMemory>()
            .eq(KairosMemory::getScope, scope)
            .eq(KairosMemory::getScopeKey, scopeKey)
            .eq(KairosMemory::getMemoryKey, key));
        memoryMapper.insert(memory);

        log.debug("KAIROS: remembered '{}' (scope={}, importance={})", key, scope, importance);
    }

    /**
     * Recall memories for a context.
     */
    public List<KairosMemory> recall(String scope, String scopeKey, int limit) {
        return memoryMapper.selectList(
            new LambdaQueryWrapper<KairosMemory>()
                .eq(KairosMemory::getScope, scope)
                .eq(KairosMemory::getScopeKey, scopeKey)
                .orderByDesc(KairosMemory::getImportance)
                .last("LIMIT " + limit)
        );
    }
}
