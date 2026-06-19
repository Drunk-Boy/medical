package cn.xu.medical.agent.permission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Gate 3: High-risk operations that require explicit user confirmation.
 */
@Slf4j
@Component
public class ExplicitConfirmGate implements PermissionGate {

    private static final List<Pattern> HIGH_RISK_PATTERNS = List.of(
        Pattern.compile("rm\\s+-(r|rf|f)", Pattern.CASE_INSENSITIVE),  // rm -r / rm -rf / rm -f
        Pattern.compile("sudo\\s+"),                                     // sudo
        Pattern.compile("chmod\\s+[0-7]*[4-7][0-7]*"),                 // chmod (with write/exec bits)
        Pattern.compile("git\\s+push\\s+.*--force"),                   // force push
        Pattern.compile("git\\s+push\\s+.*-f\\b"),                     // force push short
        Pattern.compile("DROP\\s+(TABLE|DATABASE|SCHEMA)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("TRUNCATE\\s+(TABLE\\s+)?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("DELETE\\s+FROM\\s+\\w+(\\s+WHERE)?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("ALTER\\s+TABLE\\s+\\w+\\s+DROP", Pattern.CASE_INSENSITIVE),
        Pattern.compile("shutdown\\s+(-r|-h|now)", Pattern.CASE_INSENSITIVE)
    );

    private static final String CONFIRM_KEY_PREFIX = "agent:permission:confirm:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final int confirmTimeoutSeconds;

    public ExplicitConfirmGate(RedisTemplate<String, Object> redisTemplate,
                               @Value("${agent.permission.confirm-timeout-seconds:60}") int confirmTimeoutSeconds) {
        this.redisTemplate = redisTemplate;
        this.confirmTimeoutSeconds = confirmTimeoutSeconds;
    }

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        if (ctx.getCommand() == null || ctx.getCommand().isBlank()) {
            return null; // non-shell operations handled by other rules
        }

        String cmd = ctx.getCommand().trim();
        for (Pattern pattern : HIGH_RISK_PATTERNS) {
            if (pattern.matcher(cmd).find()) {
                String token = UUID.randomUUID().toString();
                String prompt = "检测到高危操作: `" + cmd + "`\n匹配规则: " + pattern.pattern() + "\n是否确认执行?";

                // Store confirmation token in Redis with TTL
                String redisKey = CONFIRM_KEY_PREFIX + token;
                redisTemplate.opsForValue().set(redisKey,
                    new PendingConfirmation(ctx.getSessionId(), ctx.getToolName(), cmd),
                    confirmTimeoutSeconds, TimeUnit.SECONDS);

                log.warn("EXPLICIT_CONFIRM: high-risk command '{}' requires user confirmation, token={}", cmd, token);
                return PermissionDecision.needConfirm(PermissionLevel.EXPLICIT_CONFIRM,
                    "高危命令需要用户确认", token, prompt);
            }
        }

        return null; // not high-risk
    }

    @Override
    public PermissionLevel level() { return PermissionLevel.EXPLICIT_CONFIRM; }

    /**
     * Check if the user approved/rejected a pending confirmation.
     */
    public boolean resolveConfirmation(String token, boolean approved) {
        String redisKey = CONFIRM_KEY_PREFIX + token;
        PendingConfirmation pending = (PendingConfirmation) redisTemplate.opsForValue().get(redisKey);
        if (pending == null) {
            return false; // expired or invalid
        }
        redisTemplate.delete(redisKey);
        return approved;
    }

    public record PendingConfirmation(String sessionId, String toolName, String command) {}
}
