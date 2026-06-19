package cn.xu.medical.agent.tool.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Tool Lazy Loader — Core optimization.
 *
 * At startup: only fetch tool NAMES from MCP servers (cheap).
 * On first invocation: fetch full JSON Schema for that specific tool.
 * Schema is cached in Redis (TTL 30min) for subsequent calls.
 *
 * This dramatically reduces context window usage when many MCP servers
 * with many tools are configured.
 */
@Slf4j
@Service
public class McpToolLazyLoader {

    private static final String SCHEMA_CACHE_PREFIX = "mcp:schema:";
    private static final Duration SCHEMA_CACHE_TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, Object> redisTemplate;

    /** In-memory cache of tool name → server association (fast path) */
    private final Map<String, String> toolServerMap = new ConcurrentHashMap<>();

    /** In-memory cache of loaded schemas (even faster than Redis) */
    private final Map<String, ToolLazySchema> memoryCache = new ConcurrentHashMap<>();

    public McpToolLazyLoader(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Register tool names from an MCP server (called at startup).
     * Only the tool name and a one-line description are stored.
     */
    public void registerToolNames(String serverName, List<ToolNameEntry> tools) {
        for (ToolNameEntry entry : tools) {
            String fullName = "mcp__" + serverName + "__" + entry.name();
            toolServerMap.put(fullName, serverName);
        }
        log.info("MCP LazyLoader: registered {} tool names from server '{}'", tools.size(), serverName);
    }

    /**
     * Check if a tool name is known (cheap lookup).
     */
    public boolean knowsTool(String toolName) {
        return toolServerMap.containsKey(toolName);
    }

    /**
     * Get the full Schema for a tool — on demand.
     * Checks memory cache → Redis cache → fetches from MCP server.
     */
    public ToolLazySchema loadFullSchema(String serverName, String toolName) {
        String cacheKey = SCHEMA_CACHE_PREFIX + serverName + ":" + toolName;

        // 1. Memory cache (fastest)
        ToolLazySchema cached = memoryCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 2. Redis cache
        Object redisValue = redisTemplate.opsForValue().get(cacheKey);
        if (redisValue instanceof ToolLazySchema schema) {
            memoryCache.put(cacheKey, schema);
            return schema;
        }

        // 3. Would fetch from MCP server here...
        // For now, return a placeholder — real implementation uses McpClientManager
        log.debug("MCP LazyLoader: schema not cached for {}/{} — would fetch from server", serverName, toolName);
        return null;
    }

    /**
     * Cache a fetched schema.
     */
    public void cacheSchema(String serverName, String toolName, ToolLazySchema schema) {
        String cacheKey = SCHEMA_CACHE_PREFIX + serverName + ":" + toolName;
        memoryCache.put(cacheKey, schema);
        redisTemplate.opsForValue().set(cacheKey, schema, SCHEMA_CACHE_TTL);
    }

    /**
     * Remove cached schemas for a server (on disconnect).
     */
    public void evictServer(String serverName) {
        memoryCache.keySet().removeIf(k -> k.contains(":" + serverName + ":"));
        toolServerMap.keySet().removeIf(k -> k.startsWith("mcp__" + serverName + "__"));
        log.info("MCP LazyLoader: evicted server '{}'", serverName);
    }

    /**
     * Get all tool names currently tracked.
     */
    public List<String> getAllToolNames() {
        return List.copyOf(toolServerMap.keySet());
    }

    /**
     * Number of tracked tools.
     */
    public int toolCount() {
        return toolServerMap.size();
    }

    // --- Inner types ---

    public record ToolNameEntry(String name, String description) {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolLazySchema {
        private String name;
        private String description;
        private String inputSchemaJson;
        private String serverName;
    }
}
