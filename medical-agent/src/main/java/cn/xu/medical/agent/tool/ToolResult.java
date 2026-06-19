package cn.xu.medical.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unified result from any tool execution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {

    private boolean success;
    private String output;
    private String error;
    private long durationMs;

    /** Number of lines/items returned (for truncation hint) */
    private int resultCount;

    /** Whether the output was truncated */
    private boolean truncated;

    public static ToolResult ok(String output) {
        return ToolResult.builder()
            .success(true)
            .output(output)
            .build();
    }

    public static ToolResult ok(String output, int count) {
        return ToolResult.builder()
            .success(true)
            .output(output)
            .resultCount(count)
            .build();
    }

    public static ToolResult truncated(String output, int count, String hint) {
        return ToolResult.builder()
            .success(true)
            .output(output + "\n\n" + hint)
            .resultCount(count)
            .truncated(true)
            .build();
    }

    public static ToolResult fail(String error) {
        return ToolResult.builder()
            .success(false)
            .error(error)
            .build();
    }
}
