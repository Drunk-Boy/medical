package cn.xu.medical.agent.permission;

import cn.xu.medical.agent.common.entity.AgentAllowRule;
import cn.xu.medical.agent.common.mapper.AgentAllowRuleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Gate 6: Match against a configurable whitelist of safe commands.
 * Rules loaded from DB table agent_allow_rule, refreshed periodically.
 */
@Slf4j
@Component
public class AutoAllowRule implements PermissionGate {

    /** Default safe commands — always in the whitelist */
    private static final List<String> DEFAULT_SAFE_PREFIXES = List.of(
        "git status", "git log", "git diff", "git branch", "git stash",
        "git add", "git commit", "git checkout", "git merge",
        "mvn test", "mvn compile", "mvn clean", "mvn validate",
        "ls ", "cat ", "head ", "tail ", "wc ", "find ", "grep ",
        "echo ", "date", "which ", "type ", "pwd", "whoami",
        "java -version", "node -v", "npm -v", "python --version",
        "docker ps", "docker images"
    );

    private final List<Pattern> allowPatterns = new CopyOnWriteArrayList<>();
    private final AgentAllowRuleMapper allowRuleMapper;

    public AutoAllowRule(AgentAllowRuleMapper allowRuleMapper) {
        this.allowRuleMapper = allowRuleMapper;
    }

    @PostConstruct
    void init() {
        // Load defaults
        for (String prefix : DEFAULT_SAFE_PREFIXES) {
            allowPatterns.add(Pattern.compile("^" + Pattern.quote(prefix) + ".*"));
        }
        // Load from DB
        refreshFromDb();
    }

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        if (ctx.getCommand() == null || ctx.getCommand().isBlank()) {
            return null;
        }

        // Also auto-allow read-only non-shell tools via this gate
        if (ctx.isReadOnly() && ctx.getCommand() == null) {
            return PermissionDecision.allow(PermissionLevel.AUTO_ALLOW, "只读操作，自动允许");
        }

        // Check command against patterns
        String cmd = ctx.getCommand().trim();
        for (Pattern pattern : allowPatterns) {
            if (pattern.matcher(cmd).matches()) {
                log.debug("AUTO_ALLOW: command '{}' matched pattern '{}'", cmd, pattern);
                return PermissionDecision.allow(PermissionLevel.AUTO_ALLOW,
                    "命令匹配自动允许规则: " + pattern.pattern());
            }
        }

        return null; // not in whitelist
    }

    @Override
    public PermissionLevel level() { return PermissionLevel.AUTO_ALLOW; }

    /**
     * Reload rules from database.
     */
    public void refreshFromDb() {
        try {
            List<AgentAllowRule> rules = allowRuleMapper.selectList(
                new LambdaQueryWrapper<AgentAllowRule>()
                    .eq(AgentAllowRule::getEnabled, true)
                    .orderByDesc(AgentAllowRule::getPriority)
            );
            for (AgentAllowRule rule : rules) {
                String regex = rule.getPattern()
                    .replace("*", ".*")
                    .replace("?", ".");
                allowPatterns.add(0, Pattern.compile("^" + regex + ".*"));
            }
            log.info("AUTO_ALLOW: loaded {} rules from DB (total patterns: {})", rules.size(), allowPatterns.size());
        } catch (Exception e) {
            log.warn("AUTO_ALLOW: failed to load rules from DB, using defaults", e);
        }
    }
}
