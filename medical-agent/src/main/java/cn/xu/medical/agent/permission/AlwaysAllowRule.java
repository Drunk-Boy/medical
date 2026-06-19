package cn.xu.medical.agent.permission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Gate 7: Read-only tools are always allowed.
 * This is the most permissive gate and runs last.
 */
@Slf4j
@Component
public class AlwaysAllowRule implements PermissionGate {

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
        "FileReadTool", "fileReadTool", "readFile",
        "GlobTool", "globTool", "glob",
        "GrepTool", "grepTool", "grep",
        "LspTool", "lspTool",
        "BashTool", "bashTool"
    );

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        if (ctx.isReadOnly()) {
            log.debug("ALWAYS_ALLOW: read-only operation via {}", ctx.getToolName());
            return PermissionDecision.allow(PermissionLevel.ALWAYS_ALLOW,
                "只读工具，始终允许");
        }

        // BashTool with read-only commands
        if (isReadOnlyTool(ctx.getToolName()) && isReadOnlyCommand(ctx.getCommand())) {
            return PermissionDecision.allow(PermissionLevel.ALWAYS_ALLOW,
                "只读 shell 命令，始终允许");
        }

        return null; // last gate — if we reach here, default to NEED_CONFIRM in chain
    }

    @Override
    public PermissionLevel level() { return PermissionLevel.ALWAYS_ALLOW; }

    private boolean isReadOnlyTool(String toolName) {
        if (toolName == null) return false;
        return READ_ONLY_TOOLS.stream().anyMatch(t -> toolName.equalsIgnoreCase(t));
    }

    private boolean isReadOnlyCommand(String command) {
        if (command == null || command.isBlank()) return true;
        String cmd = command.trim().toLowerCase();
        return cmd.startsWith("ls ") || cmd.startsWith("cat ") || cmd.startsWith("head ")
            || cmd.startsWith("tail ") || cmd.startsWith("wc ") || cmd.startsWith("find ")
            || cmd.startsWith("grep ") || cmd.equals("pwd") || cmd.startsWith("echo ")
            || cmd.startsWith("git status") || cmd.startsWith("git log") || cmd.startsWith("git diff")
            || cmd.startsWith("git branch") || cmd.startsWith("which ") || cmd.startsWith("type ")
            || cmd.equals("date") || cmd.equals("whoami") || cmd.startsWith("du ")
            || cmd.startsWith("df ") || cmd.startsWith("file ");
    }
}
