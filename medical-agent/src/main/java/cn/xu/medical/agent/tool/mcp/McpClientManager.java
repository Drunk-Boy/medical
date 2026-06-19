package cn.xu.medical.agent.tool.mcp;

import cn.xu.medical.agent.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages MCP server connections, reconnection, and lifecycle.
 */
@Slf4j
@Service
public class McpClientManager {

    private final Map<String, McpConnectionState> connections = new ConcurrentHashMap<>();
    private final McpToolLazyLoader lazyLoader;
    private final ToolRegistry toolRegistry;
    private final RedisTemplate<String, Object> redisTemplate;
    private final McpConfigMerger configMerger;

    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    public McpClientManager(McpToolLazyLoader lazyLoader,
                            ToolRegistry toolRegistry,
                            RedisTemplate<String, Object> redisTemplate,
                            McpConfigMerger configMerger) {
        this.lazyLoader = lazyLoader;
        this.toolRegistry = toolRegistry;
        this.redisTemplate = redisTemplate;
        this.configMerger = configMerger;
    }

    @PostConstruct
    public void init() {
        log.info("MCP Client Manager initialized");
    }

    /** Connect to an MCP server */
    public boolean connect(McpServerConfig config) {
        String name = config.getName();
        if (connections.containsKey(name)) {
            log.warn("MCP server '{}' already connected or connecting", name);
            return false;
        }

        try {
            McpConnectionState state = new McpConnectionState(config, McpConnectionState.Status.CONNECTING);
            connections.put(name, state);

            // Simulate connection — in production, this would:
            // 1. Establish transport (STDIO/SSE/HTTP)
            // 2. Perform MCP handshake (initialize)
            // 3. Fetch tool list via tools/list (names only for lazy loading)
            // 4. Register tool names in lazy loader

            List<McpToolLazyLoader.ToolNameEntry> toolNames = fetchToolNames(config);
            lazyLoader.registerToolNames(name, toolNames);

            state.setStatus(McpConnectionState.Status.CONNECTED);
            state.setToolCount(toolNames.size());

            // Publish status to Redis
            redisTemplate.opsForHash().put("mcp:connections", name, "connected");
            redisTemplate.convertAndSend("mcp:status", Map.of("server", name, "status", "connected", "toolCount", toolNames.size()));

            log.info("MCP server '{}' connected with {} tools (lazy-loaded)", name, toolNames.size());
            return true;

        } catch (Exception e) {
            log.error("Failed to connect MCP server '{}': {}", name, e.getMessage());
            connections.get(name).setStatus(McpConnectionState.Status.FAILED);
            redisTemplate.opsForHash().put("mcp:connections", name, "failed");
            return false;
        }
    }

    /** Disconnect an MCP server */
    public void disconnect(String name) {
        McpConnectionState state = connections.remove(name);
        if (state != null) {
            lazyLoader.evictServer(name);
            toolRegistry.unregisterByPrefix("mcp__" + name + "__");
            redisTemplate.opsForHash().put("mcp:connections", name, "disconnected");
            log.info("MCP server '{}' disconnected", name);
        }
    }

    /** Get connection status */
    public McpConnectionState getStatus(String name) {
        return connections.get(name);
    }

    /** List all connected servers */
    public Set<String> listServers() {
        return connections.keySet();
    }

    /**
     * Reconnect with exponential backoff.
     */
    public void attemptReconnect(String name) {
        McpConnectionState state = connections.get(name);
        if (state == null || state.getReconnectAttempts() >= MAX_RECONNECT_ATTEMPTS) {
            log.warn("MCP server '{}' reconnect attempts exhausted", name);
            if (state != null) state.setStatus(McpConnectionState.Status.FAILED);
            return;
        }

        long delayMs = (long) Math.pow(2, state.getReconnectAttempts()) * 1000;
        state.incrementReconnectAttempts();

        log.info("MCP server '{}' reconnecting in {}ms (attempt {}/{})",
            name, delayMs, state.getReconnectAttempts(), MAX_RECONNECT_ATTEMPTS);

        // In production: schedule reconnect with delay
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                connect(state.getConfig());
            } catch (InterruptedException ignored) {}
        }).start();
    }

    /** Simulate fetching tool names from MCP server */
    private List<McpToolLazyLoader.ToolNameEntry> fetchToolNames(McpServerConfig config) {
        // In production, this calls MCP tools/list endpoint
        // For now, return empty — real tools are registered by MCP server
        return List.of();
    }
}
