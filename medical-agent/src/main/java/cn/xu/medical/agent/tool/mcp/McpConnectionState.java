package cn.xu.medical.agent.tool.mcp;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime state of an MCP server connection.
 */
@Data
public class McpConnectionState {

    public enum Status { CONNECTING, CONNECTED, DISCONNECTED, FAILED }

    private final McpServerConfig config;
    private volatile Status status;
    private volatile int toolCount;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final LocalDateTime createdAt = LocalDateTime.now();
    private volatile LocalDateTime lastConnectedAt;

    public McpConnectionState(McpServerConfig config, Status status) {
        this.config = config;
        this.status = status;
        if (status == Status.CONNECTED) {
            this.lastConnectedAt = LocalDateTime.now();
        }
    }

    public int getReconnectAttempts() {
        return reconnectAttempts.get();
    }

    public void incrementReconnectAttempts() {
        reconnectAttempts.incrementAndGet();
    }

    public void setStatus(Status status) {
        this.status = status;
        if (status == Status.CONNECTED) {
            this.lastConnectedAt = LocalDateTime.now();
            this.reconnectAttempts.set(0);
        }
    }
}
