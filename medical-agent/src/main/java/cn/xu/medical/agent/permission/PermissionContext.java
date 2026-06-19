package cn.xu.medical.agent.permission;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.Map;

/**
 * Context passed through the permission pipeline.
 */
@Data
@Builder
public class PermissionContext {

    /** Session ID */
    private String sessionId;

    /** Tool name being invoked */
    private String toolName;

    /** Tool parameters */
    private Map<String, Object> toolParams;

    /** Full shell command (for BashTool) */
    private String command;

    /** Target file/directory paths (absolute) */
    private Path[] targetPaths;

    /** Project root directory */
    private Path projectRoot;

    /** Is this a read-only operation? */
    private boolean readOnly;
}
