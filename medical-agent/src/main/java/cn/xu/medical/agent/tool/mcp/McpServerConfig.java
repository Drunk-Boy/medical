package cn.xu.medical.agent.tool.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP server configuration, supporting multiple transport types.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerConfig {

    private String name;
    private TransportType transport;
    private String url;                // HTTP/SSE/WS URL
    private String command;            // STDIO command
    private List<String> args;         // STDIO arguments
    private Map<String, String> env;   // environment variables
    private Map<String, String> headers; // HTTP headers
    private String scope;              // MANAGED / PROJECT / USER
    private boolean enabled = true;
    private int timeoutMs = 60000;     // connection timeout
    private int toolTimeoutMs = 300000; // per-tool execution timeout
    private boolean alwaysLoad = false; // skip lazy loading, load all schemas at startup

    public enum TransportType {
        STDIO,
        SSE,
        STREAMABLE_HTTP,
        WEBSOCKET
    }
}
