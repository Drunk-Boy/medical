package cn.xu.medical.agent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {
    private EventType type;
    private String messageId;
    private String content;
    private String toolName;
    private Map<String, Object> params;
    private String callId;
    private Boolean success;
    private String output;
    private Long durationMs;
    private String errorCode;
    private String errorMessage;
    private Integer totalTokens;
    private Integer toolCalls;
    private LocalDateTime timestamp;

    public enum EventType {
        THINKING,
        TEXT_CHUNK,
        TOOL_CALL_START,
        TOOL_CALL_PROGRESS,
        TOOL_CALL_RESULT,
        PERMISSION_REQUIRED,
        ERROR,
        DONE
    }

    public static AgentEvent textChunk(String msgId, String content) {
        return AgentEvent.builder()
            .type(EventType.TEXT_CHUNK)
            .messageId(msgId)
            .content(content)
            .timestamp(LocalDateTime.now())
            .build();
    }

    public static AgentEvent toolCallStart(String msgId, String toolName, Map<String, Object> params, String callId) {
        return AgentEvent.builder()
            .type(EventType.TOOL_CALL_START)
            .messageId(msgId)
            .toolName(toolName)
            .params(params)
            .callId(callId)
            .timestamp(LocalDateTime.now())
            .build();
    }

    public static AgentEvent toolCallResult(String callId, boolean success, String output, long durationMs) {
        return AgentEvent.builder()
            .type(EventType.TOOL_CALL_RESULT)
            .callId(callId)
            .success(success)
            .output(output)
            .durationMs(durationMs)
            .timestamp(LocalDateTime.now())
            .build();
    }

    public static AgentEvent permissionRequired(String callId, String toolName, String command) {
        return AgentEvent.builder()
            .type(EventType.PERMISSION_REQUIRED)
            .callId(callId)
            .toolName(toolName)
            .content(command)
            .timestamp(LocalDateTime.now())
            .build();
    }

    public static AgentEvent error(String msgId, String code, String message) {
        return AgentEvent.builder()
            .type(EventType.ERROR)
            .messageId(msgId)
            .errorCode(code)
            .errorMessage(message)
            .timestamp(LocalDateTime.now())
            .build();
    }

    public static AgentEvent done(String msgId, int totalTokens, int toolCalls) {
        return AgentEvent.builder()
            .type(EventType.DONE)
            .messageId(msgId)
            .totalTokens(totalTokens)
            .toolCalls(toolCalls)
            .timestamp(LocalDateTime.now())
            .build();
    }
}
