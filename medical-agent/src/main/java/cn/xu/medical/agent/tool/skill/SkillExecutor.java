package cn.xu.medical.agent.tool.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Executes a skill, either inline (in current context) or in a fork (sub-agent).
 */
@Slf4j
@Component
public class SkillExecutor {

    private final ChatModel chatModel;
    private final SkillRegistry registry;

    public SkillExecutor(ChatModel chatModel, SkillRegistry registry) {
        this.chatModel = chatModel;
        this.registry = registry;
    }

    /**
     * Execute a skill by name.
     *
     * @param skillName  skill name (without / prefix)
     * @param args       positional arguments for $1, $2, etc.
     * @param inline     true = inject into current context, false = run as sub-agent
     * @return the skill's output (summary if fork mode, instructions if inline)
     */
    public String execute(String skillName, Map<String, String> args, boolean inline) {
        Skill skill = registry.getOrThrow(skillName);

        // Resolve argument substitutions
        String resolvedBody = resolveArgs(skill.getBody(), skill.getArguments(), args);

        if ("fork".equals(skill.getContext()) || !inline) {
            return executeAsSubAgent(skill, resolvedBody);
        } else {
            return executeInline(skill, resolvedBody);
        }
    }

    /**
     * Inline execution: return the skill instructions so they're injected into system prompt.
     */
    private String executeInline(Skill skill, String resolvedBody) {
        log.info("Executing skill '{}' inline", skill.getName());
        return resolvedBody;
    }

    /**
     * Fork execution: run in a separate ChatClient instance.
     */
    private String executeAsSubAgent(Skill skill, String resolvedBody) {
        log.info("Executing skill '{}' in fork mode", skill.getName());

        try {
            String response = ChatClient.create(chatModel)
                .prompt()
                .system("你是一个执行以下技能的专业助手。严格按照技能说明执行，完成后返回摘要。")
                .user(resolvedBody)
                .call()
                .content();

            return "## Skill " + skill.getCommandName() + " 执行结果\n\n" + response;
        } catch (Exception e) {
            log.error("Skill '{}' execution failed: {}", skill.getName(), e.getMessage());
            return "技能执行失败: " + e.getMessage();
        }
    }

    /**
     * Replace $1, $2 / $name placeholders with actual arguments.
     */
    private String resolveArgs(String body, java.util.List<String> argNames, Map<String, String> args) {
        if (body == null) return "";
        String resolved = body;

        // Positional: $1, $2, ...
        if (argNames != null) {
            for (int i = 0; i < argNames.size(); i++) {
                String value = args.getOrDefault(String.valueOf(i + 1),
                    args.getOrDefault(argNames.get(i), ""));
                resolved = resolved.replace("$" + (i + 1), value);
                resolved = resolved.replace("$" + argNames.get(i), value);
            }
        }

        // Named: $name
        for (Map.Entry<String, String> entry : args.entrySet()) {
            resolved = resolved.replace("$" + entry.getKey(), entry.getValue());
        }

        // Dynamic content: !`command` → execute and inline output
        resolved = resolveDynamicContent(resolved);

        return resolved;
    }

    /**
     * Resolve !`command` dynamic content injections.
     */
    private String resolveDynamicContent(String body) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < body.length()) {
            if (body.charAt(i) == '!' && i + 1 < body.length() && body.charAt(i + 1) == '`') {
                int end = body.indexOf('`', i + 2);
                if (end > 0) {
                    String command = body.substring(i + 2, end);
                    try {
                        ProcessBuilder pb = new ProcessBuilder();
                        pb.command(System.getProperty("os.name").toLowerCase().contains("win")
                            ? new String[]{"cmd", "/c", command}
                            : new String[]{"sh", "-c", command});
                        pb.redirectErrorStream(true);
                        Process process = pb.start();
                        String output = new String(process.getInputStream().readAllBytes());
                        process.waitFor();
                        result.append(output.trim());
                    } catch (Exception e) {
                        result.append("[!执行失败: ").append(command).append(" - ").append(e.getMessage()).append("]");
                    }
                    i = end + 1;
                    continue;
                }
            }
            result.append(body.charAt(i));
            i++;
        }
        return result.toString();
    }
}
