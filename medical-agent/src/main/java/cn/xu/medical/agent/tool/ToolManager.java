package cn.xu.medical.agent.tool;

import cn.xu.medical.agent.tool.builtin.*;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Central tool manager — collects all tool instances.
 * Spring AI ChatClient automatically scans @Tool annotations on these instances.
 */
@Slf4j
@Service
public class ToolManager {

    @Getter
    private final ToolRegistry registry = new ToolRegistry();

    private final List<Object> toolInstances = new ArrayList<>();

    public ToolManager(FileReadTool fileReadTool, FileWriteTool fileWriteTool,
                       FileEditTool fileEditTool, GlobTool globTool,
                       GrepTool grepTool, BashTool bashTool) {
        toolInstances.addAll(List.of(
            fileReadTool, fileWriteTool, fileEditTool,
            globTool, grepTool, bashTool
        ));
    }

    @PostConstruct
    public void init() {
        log.info("ToolManager initialized with {} built-in tools", toolInstances.size());

        // Register in our local registry (for name-based lookup)
        for (Object tool : toolInstances) {
            for (var method : tool.getClass().getDeclaredMethods()) {
                var toolAnn = method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
                if (toolAnn != null) {
                    String name = toolAnn.name();
                    if (name == null || name.isBlank()) {
                        name = method.getName();
                    }
                    registry.registerName(name);
                    log.debug("Registered tool: {}", name);
                }
            }
        }
    }

    /**
     * Return all tool instances for ChatClient.tools().
     * Spring AI auto-discovers @Tool methods on these objects.
     */
    public Object[] getToolObjects() {
        return toolInstances.toArray();
    }

    /**
     * Register an external MCP-discovered tool callback.
     */
    public void registerMcpTool(Object tool) {
        toolInstances.add(tool);
        log.debug("Registered MCP tool");
    }

    /**
     * Unregister all tools from a specific MCP server.
     */
    public void unregisterMcpServer(String serverName) {
        registry.unregisterByPrefix("mcp__" + serverName + "__");
        toolInstances.removeIf(t -> t.getClass().getName().contains(serverName));
        log.info("Unregistered tools from MCP server: {}", serverName);
    }
}
