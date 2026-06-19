package cn.xu.medical.agent.permission;

import cn.xu.medical.agent.common.entity.PermissionAudit;
import cn.xu.medical.agent.common.mapper.PermissionAuditMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Chains all seven permission gates in priority order.
 * First gate that returns a non-null decision wins.
 * If no gate rules, defaults to NEED_CONFIRM (conservative).
 */
@Slf4j
@Service
public class PermissionChain {

    private final List<PermissionGate> gates;
    private final PermissionAuditMapper auditMapper;
    private final SessionAllowCache sessionAllowCache;
    private final ObjectMapper objectMapper;

    public PermissionChain(GlobalDenyRule globalDeny,
                           DirectoryScopedGate directoryScoped,
                           ExplicitConfirmGate explicitConfirm,
                           MlClassifierGate mlClassifier,
                           SessionAllowCache sessionAllow,
                           AutoAllowRule autoAllow,
                           AlwaysAllowRule alwaysAllow,
                           PermissionAuditMapper auditMapper,
                           SessionAllowCache sessionAllowCache,
                           ObjectMapper objectMapper) {
        // Ordered by priority (highest first)
        this.gates = List.of(globalDeny, directoryScoped, explicitConfirm,
            mlClassifier, sessionAllow, autoAllow, alwaysAllow);
        this.auditMapper = auditMapper;
        this.sessionAllowCache = sessionAllowCache;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluate a tool invocation through the permission pipeline.
     */
    public PermissionDecision evaluate(PermissionContext ctx) {
        for (PermissionGate gate : gates) {
            PermissionDecision decision = gate.evaluate(ctx);
            if (decision != null) {
                // Log audit
                audit(ctx, decision);
                log.info("PERMISSION: {} → {} (reason: {})", ctx.getToolName(), decision.getDecision(), decision.getReason());
                return decision;
            }
        }

        // Fallthrough: conservative default
        PermissionDecision fallback = PermissionDecision.needConfirm(
            PermissionLevel.EXPLICIT_CONFIRM,
            "未匹配任何允许规则，需要用户确认",
            null,
            "操作 `" + ctx.getToolName() + "` 需要确认执行"
        );
        audit(ctx, fallback);
        return fallback;
    }

    /**
     * Record a user's approval and cache it for the session.
     */
    public void recordApproval(String sessionId, PermissionContext ctx) {
        sessionAllowCache.approve(sessionId, ctx.getToolName(),
            ctx.getCommand() != null ? ctx.getCommand() : "");
    }

    /**
     * Clear session approvals.
     */
    public void clearSession(String sessionId) {
        sessionAllowCache.clearSession(sessionId);
    }

    private void audit(PermissionContext ctx, PermissionDecision decision) {
        try {
            PermissionAudit audit = PermissionAudit.builder()
                .sessionId(ctx.getSessionId())
                .toolName(ctx.getToolName())
                .toolParams(ctx.getToolParams() != null ? objectMapper.writeValueAsString(ctx.getToolParams()) : null)
                .decisionLevel(decision.getLevel() != null ? decision.getLevel().name() : "UNKNOWN")
                .decision(decision.getDecision().name())
                .reason(decision.getReason())
                .build();
            auditMapper.insert(audit);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize tool params for audit", e);
        } catch (Exception e) {
            log.error("Failed to write permission audit", e);
        }
    }
}
