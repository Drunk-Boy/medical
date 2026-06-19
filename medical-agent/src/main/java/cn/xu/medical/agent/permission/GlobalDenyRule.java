package cn.xu.medical.agent.permission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Gate 1: Hardcoded forbidden patterns — immediate DENY.
 */
@Slf4j
@Component
public class GlobalDenyRule implements PermissionGate {

    // Literal substrings that are always denied
    private static final List<String> DENY_LITERALS = List.of(
        "rm -rf /", "rm -rf /*", "rm -rf ~", "rm -rf $HOME",
        "sudo rm -rf /", "sudo rm -rf /*"
    );

    // Regex patterns for more complex matching
    private static final List<Pattern> DENY_PATTERNS = List.of(
        Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),       // curl | bash
        Pattern.compile("wget\\s+.*\\|\\s*(ba)?sh"),       // wget | bash
        Pattern.compile("chmod\\s+777\\s+/"),               // chmod 777 /
        Pattern.compile(">\\s*/dev/sd[a-z]"),               // write to block device
        Pattern.compile("mkfs\\.\\w+\\s+/dev/"),            // format filesystem
        Pattern.compile("dd\\s+if=.*of=/dev/"),             // dd to block device
        Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;:"), // fork bomb
        Pattern.compile("sudo\\s+rm\\s+-rf")                // sudo rm -rf
    );

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        if (ctx.getCommand() == null || ctx.getCommand().isBlank()) {
            return null;
        }

        String cmd = ctx.getCommand().trim();
        String cmdLower = cmd.toLowerCase();

        // Check literal substrings
        for (String literal : DENY_LITERALS) {
            if (cmdLower.contains(literal.toLowerCase())) {
                log.warn("GLOBAL_DENY: blocked dangerous command '{}' matched literal '{}'", cmd, literal);
                return PermissionDecision.deny(PermissionLevel.GLOBAL_DENY,
                    "命令匹配全局禁止模式: " + literal);
            }
        }

        // Check regex patterns
        for (Pattern pattern : DENY_PATTERNS) {
            if (pattern.matcher(cmdLower).find()) {
                log.warn("GLOBAL_DENY: blocked dangerous command '{}' matched pattern '{}'", cmd, pattern);
                return PermissionDecision.deny(PermissionLevel.GLOBAL_DENY,
                    "命令匹配全局禁止模式: " + pattern.pattern());
            }
        }

        return null;
    }

    @Override
    public PermissionLevel level() { return PermissionLevel.GLOBAL_DENY; }
}
