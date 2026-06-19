package cn.xu.medical.agent.context;

import cn.xu.medical.agent.tool.ToolRegistry;
import cn.xu.medical.agent.tool.skill.SkillRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the system prompt for the Agent.
 * Includes AGENTS.md, skill list, tool summary, and environment info.
 */
@Component
public class SystemPromptBuilder {

    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final Path projectRoot;

    public SystemPromptBuilder(ToolRegistry toolRegistry,
                               SkillRegistry skillRegistry,
                               @Value("${agent.project-root:#{systemProperties['user.dir']}}") String projectRoot) {
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.projectRoot = Path.of(projectRoot);
    }

    public String build() {
        StringBuilder sb = new StringBuilder();

        // Core identity
        sb.append("""
            你是一个 AI 编程 Agent（基于 Medical Agent 框架），运行在用户的开发环境中。
            你的职责是帮助用户编写、调试和优化代码，管理项目，回答技术问题。
            
            核心规则：
            1. 使用提供的工具来读取文件、搜索代码、执行命令
            2. 编辑文件前确保理解上下文，修改最小化
            3. 执行危险命令前请求用户确认
            4. 如果问题超出你的能力范围，诚实告知并建议下一步
            5. 优先阅读 AGENTS.md（如果存在）了解项目约定
            
            """);

        // AGENTS.md — project-specific instructions
        Path agentsMd = projectRoot.resolve("AGENTS.md");
        if (Files.isRegularFile(agentsMd)) {
            try {
                String content = Files.readString(agentsMd);
                sb.append("## 项目约定 (AGENTS.md)\n\n").append(content).append("\n\n");
            } catch (Exception ignored) {}
        }

        // Tools summary (names only — schemas are provided by Spring AI)
        sb.append("## 可用工具\n\n");
        for (String name : toolRegistry.getToolNames()) {
            sb.append("- **").append(name).append("**\n");
        }
        sb.append("\n");

        // Skills
        String skillList = skillRegistry.getSkillListForPrompt();
        if (!skillList.isBlank()) {
            sb.append(skillList).append("\n");
        }

        // Environment info
        sb.append("## 环境信息\n");
        sb.append("- 操作系统: ").append(System.getProperty("os.name")).append("\n");
        sb.append("- Java 版本: ").append(System.getProperty("java.version")).append("\n");
        sb.append("- 项目目录: ").append(projectRoot.toAbsolutePath()).append("\n");
        sb.append("- 当前时间: ").append(java.time.LocalDateTime.now()).append("\n");

        return sb.toString();
    }

    /**
     * Build a minimal system prompt for lightweight operations (ML classifier, etc.)
     */
    public String buildMinimal() {
        return """
            你是一个代码分析助手。回答简洁准确。
            只输出要求的内容，不要多余解释。
            """;
    }
}
