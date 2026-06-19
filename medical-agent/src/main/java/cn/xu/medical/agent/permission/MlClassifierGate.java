package cn.xu.medical.agent.permission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Gate 4: Use a lightweight LLM call to classify command danger.
 * Results are cached in Redis to avoid repeated API calls.
 */
@Slf4j
@Component
public class MlClassifierGate implements PermissionGate {

    private static final String CACHE_KEY_PREFIX = "agent:mlclassify:";
    private static final String CLASSIFY_PROMPT = """
        判断以下 shell 命令是否危险。仅回复 SAFE 或 DANGEROUS 一个词。
        
        危险标准（任一即危险）：
        - 删除文件或目录
        - 修改系统配置或权限
        - 访问 /etc、/root、/boot 等敏感路径
        - 执行未知来源的脚本（curl/wget | sh）
        - 网络数据外发（nc、scp 到外网）
        - 提权操作（sudo、su）
        - 数据库删表/删库
        
        安全标准：
        - 只读操作（cat、ls、grep、find、git log/status/diff）
        - 构建/测试（mvn、gradle、npm、cargo 等）
        - 版本控制（git add/commit/branch/checkout，不含 force push）
        
        命令：%s
        
        回复（仅 SAFE 或 DANGEROUS）：""";

    private final ChatModel chatModel;
    private final RedisTemplate<String, Object> redisTemplate;
    private final int cacheTtlMinutes;

    public MlClassifierGate(ChatModel chatModel,
                            RedisTemplate<String, Object> redisTemplate,
                            @Value("${agent.permission.ml-classifier-cache-ttl-minutes:10}") int cacheTtlMinutes) {
        this.chatModel = chatModel;
        this.redisTemplate = redisTemplate;
        this.cacheTtlMinutes = cacheTtlMinutes;
    }

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        if (ctx.getCommand() == null || ctx.getCommand().isBlank()) {
            return null; // no command to classify
        }

        String cmd = ctx.getCommand().trim();
        String cacheKey = CACHE_KEY_PREFIX + sha256(cmd);

        // Check cache
        String cached = (String) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached.equals("DANGEROUS")
                ? PermissionDecision.needConfirm(PermissionLevel.ML_CLASSIFIER,
                    "ML 分类器判定为危险操作（缓存）", null, "ML 判定 `" + cmd + "` 为危险操作，是否继续？")
                : null; // SAFE → pass to next gate
        }

        try {
            String prompt = String.format(CLASSIFY_PROMPT, cmd);
            String response = chatModel.call(prompt).trim().toUpperCase();
            boolean dangerous = response.contains("DANGEROUS");

            // Cache result
            redisTemplate.opsForValue().set(cacheKey, dangerous ? "DANGEROUS" : "SAFE",
                Duration.ofMinutes(cacheTtlMinutes));

            log.debug("ML_CLASSIFIER: command '{}' classified as {}", cmd, dangerous ? "DANGEROUS" : "SAFE");

            if (dangerous) {
                return PermissionDecision.needConfirm(PermissionLevel.ML_CLASSIFIER,
                    "ML 分类器判定为危险操作", null, "ML 判定 `" + cmd + "` 为危险操作，是否继续？");
            }
            return null; // SAFE → pass to next gate

        } catch (Exception e) {
            log.error("ML_CLASSIFIER: classification failed for '{}'", cmd, e);
            // On error, fall through to next gate (conservative: will be caught by later gates)
            return null;
        }
    }

    @Override
    public PermissionLevel level() { return PermissionLevel.ML_CLASSIFIER; }

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
