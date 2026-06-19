package cn.xu.medical.agent.permission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Gate 5: Check if this exact operation was previously approved in this session.
 * Uses Redis Hash: agent:session:{sessionId}:allowed → {tool:paramHash} → "1"
 */
@Slf4j
@Component
public class SessionAllowCache implements PermissionGate {

    private static final String KEY_PREFIX = "agent:session:allowed:";

    private final RedisTemplate<String, Object> redisTemplate;

    public SessionAllowCache(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        if (ctx.getSessionId() == null) {
            return null; // no session context
        }

        String hash = computeHash(ctx);
        String hashKey = KEY_PREFIX + ctx.getSessionId();

        Boolean exists = redisTemplate.opsForHash().hasKey(hashKey, hash);
        if (Boolean.TRUE.equals(exists)) {
            log.debug("SESSION_ALLOW: previously approved — session={}, hash={}", ctx.getSessionId(), hash);
            return PermissionDecision.allow(PermissionLevel.SESSION_ALLOW,
                "当前会话中已授权此操作");
        }

        return null; // not yet approved this session
    }

    /**
     * Record that this operation was approved by user.
     */
    public void approve(String sessionId, String toolName, String params) {
        String hashKey = KEY_PREFIX + sessionId;
        String hash = sha256(toolName + ":" + params);
        redisTemplate.opsForHash().put(hashKey, hash, "1");
        log.debug("SESSION_ALLOW: recorded approval — session={}, tool={}", sessionId, toolName);
    }

    /**
     * Clear all approvals for a session (on session close).
     */
    public void clearSession(String sessionId) {
        String hashKey = KEY_PREFIX + sessionId;
        redisTemplate.delete(hashKey);
    }

    @Override
    public PermissionLevel level() { return PermissionLevel.SESSION_ALLOW; }

    private String computeHash(PermissionContext ctx) {
        String input = ctx.getToolName() + ":" + ctx.getCommand();
        return sha256(input != null ? input : "");
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
