package cn.xu.medical.agent.permission;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionDecision {

    /** Final decision */
    private Decision decision;

    /** Which level made the decision */
    private PermissionLevel level;

    /** Human-readable reason */
    private String reason;

    /** Confirmation token (for EXPLICIT_CONFIRM / ML_CLASSIFIER → NEED_CONFIRM) */
    private String confirmToken;

    /** The command/params that need confirmation display */
    private String confirmPrompt;

    /** When the decision was made */
    private LocalDateTime decidedAt;

    public enum Decision {
        ALLOW,
        DENY,
        NEED_CONFIRM
    }

    // --- Factory methods ---

    public static PermissionDecision allow(PermissionLevel level, String reason) {
        return PermissionDecision.builder()
            .decision(Decision.ALLOW)
            .level(level)
            .reason(reason)
            .decidedAt(LocalDateTime.now())
            .build();
    }

    public static PermissionDecision deny(PermissionLevel level, String reason) {
        return PermissionDecision.builder()
            .decision(Decision.DENY)
            .level(level)
            .reason(reason)
            .decidedAt(LocalDateTime.now())
            .build();
    }

    public static PermissionDecision needConfirm(PermissionLevel level, String reason, String token, String prompt) {
        return PermissionDecision.builder()
            .decision(Decision.NEED_CONFIRM)
            .level(level)
            .reason(reason)
            .confirmToken(token)
            .confirmPrompt(prompt)
            .decidedAt(LocalDateTime.now())
            .build();
    }

    public boolean isAllowed() { return decision == Decision.ALLOW; }
    public boolean isDenied() { return decision == Decision.DENY; }
    public boolean needsConfirmation() { return decision == Decision.NEED_CONFIRM; }
}
