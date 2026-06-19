package cn.xu.medical.agent.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all tools (built-in + MCP-discovered + skill).
 * Thread-safe, supports dynamic add/remove.
 */
@Component
public class ToolRegistry {

    private final ConcurrentHashMap<String, ToolCallback> tools = new ConcurrentHashMap<>();
    private final java.util.Set<String> toolNames = ConcurrentHashMap.newKeySet();

    /** Register a tool */
    public void register(ToolCallback callback) {
        tools.put(callback.getToolDefinition().name(), callback);
    }

    /** Register a tool name only (for lightweight listing) */
    public void registerName(String name) {
        toolNames.add(name);
    }

    /** Register multiple tools */
    public void registerAll(Collection<ToolCallback> callbacks) {
        callbacks.forEach(this::register);
    }

    /** Remove a tool */
    public void unregister(String toolName) {
        tools.remove(toolName);
    }

    /** Remove all tools from a source (e.g., MCP server) */
    public void unregisterByPrefix(String prefix) {
        tools.keySet().removeIf(name -> name.startsWith(prefix));
    }

    /** Get all ToolCallbacks for ChatClient */
    public List<ToolCallback> getAllToolCallbacks() {
        return List.copyOf(tools.values());
    }

    /** Get all tool definitions (lightweight — names + descriptions only) */
    public List<ToolDefinition> getAllDefinitions() {
        return tools.values().stream()
            .map(ToolCallback::getToolDefinition)
            .toList();
    }

    /** Get a specific tool */
    public ToolCallback get(String toolName) {
        return tools.get(toolName);
    }

    /** Get tool names only (for lazy loading / MCP) */
    public List<String> getToolNames() {
        List<String> names = new java.util.ArrayList<>(tools.keySet());
        names.addAll(toolNames);
        return names.stream().distinct().toList();
    }

    /** How many tools registered */
    public int size() {
        return tools.size();
    }

    /** Export as map (for serialization) */
    public Map<String, ToolDefinition> export() {
        Map<String, ToolDefinition> map = new ConcurrentHashMap<>();
        tools.forEach((name, callback) -> map.put(name, callback.getToolDefinition()));
        return map;
    }

    /** Clear all tools */
    public void clear() {
        tools.clear();
        toolNames.clear();
    }
}
