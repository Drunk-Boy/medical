package cn.xu.medical.agent.tool.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements the /skillify command: learns a skill from user demonstration.
 *
 * Flow:
 * 1. User: /skillify deploy
 * 2. Agent: "Recording started. Perform the steps, then type /end-skillify"
 * 3. User: [performs actions — each tool call is recorded]
 * 4. User: /end-skillify
 * 5. Agent: Generates SKILL.md from recorded steps using LLM, writes to .agent/skills/
 */
@Slf4j
@Component
public class SkillifyCommand {

    private final Map<String, SkillifySession> activeSessions = new ConcurrentHashMap<>();
    private final ChatModel chatModel;
    private final SkillRegistry registry;
    private final SkillLoader skillLoader;
    private final Path projectSkillsDir;

    public SkillifyCommand(ChatModel chatModel,
                           SkillRegistry registry,
                           SkillLoader skillLoader,
                           @Value("${agent.project-root:#{systemProperties['user.dir']}}") String projectRoot) {
        this.chatModel = chatModel;
        this.registry = registry;
        this.skillLoader = skillLoader;
        this.projectSkillsDir = Path.of(projectRoot, ".agent", "skills");
    }

    /**
     * Start recording a demonstration for the given skill name.
     */
    public String startRecording(String skillName) {
        if (activeSessions.containsKey(skillName)) {
            return "技能 '" + skillName + "' 已经在录制中。输入 /end-skillify 结束录制。";
        }
        activeSessions.put(skillName, new SkillifySession(skillName));
        log.info("Skillify: started recording '{}'", skillName);
        return "📹 开始录制技能 '" + skillName + "'。请演示操作步骤，完成后输入 /end-skillify。";
    }

    /**
     * Record a tool call during demonstration.
     */
    public void recordAction(String skillName, String toolName, String params, String result) {
        SkillifySession session = activeSessions.get(skillName);
        if (session != null) {
            session.addAction(new RecordedAction(toolName, params, result));
        }
    }

    /**
     * Stop recording and generate the SKILL.md file.
     */
    public String stopRecording(String skillName) {
        SkillifySession session = activeSessions.remove(skillName);
        if (session == null) {
            return "没有正在录制的技能 '" + skillName + "'。使用 /skillify <name> 开始录制。";
        }

        if (session.getActions().isEmpty()) {
            return "未记录到任何操作。技能 '" + skillName + "' 未创建。";
        }

        try {
            String skillMd = generateSkillMd(skillName, session);
            Path skillDir = projectSkillsDir.resolve(skillName);
            Files.createDirectories(skillDir);
            Path skillFile = skillDir.resolve("SKILL.md");
            Files.writeString(skillFile, skillMd);

            // Reload
            Skill skill = skillLoader.parseSkillFile(skillFile, skillName, "PROJECT");
            registry.register(skill);

            log.info("Skillify: created skill '{}' at {}", skillName, skillFile);
            return "✅ 技能 '/" + skillName + "' 已创建！\n" +
                "文件: " + skillFile + "\n" +
                "录制了 " + session.getActions().size() + " 个操作步骤。\n" +
                "使用 /" + skillName + " 来执行此技能。";

        } catch (IOException e) {
            log.error("Failed to write skill file for '{}'", skillName, e);
            return "创建技能文件失败: " + e.getMessage();
        }
    }

    /**
     * Check if a skillify session is active.
     */
    public boolean isRecording(String skillName) {
        return activeSessions.containsKey(skillName);
    }

    /**
     * Use DeepSeek to generate SKILL.md from recorded actions.
     */
    private String generateSkillMd(String skillName, SkillifySession session) {
        StringBuilder actionsLog = new StringBuilder();
        for (int i = 0; i < session.getActions().size(); i++) {
            RecordedAction action = session.getActions().get(i);
            actionsLog.append("Step ").append(i + 1).append(":\n");
            actionsLog.append("  Tool: ").append(action.toolName()).append("\n");
            actionsLog.append("  Params: ").append(action.params()).append("\n");
            actionsLog.append("  Result: ").append(action.result().substring(0, Math.min(200, action.result().length()))).append("\n\n");
        }

        String prompt = """
            你是一个技能定义专家。根据以下用户演示的操作记录，生成一个 SKILL.md 文件。
            
            操作记录：
            %s
            
            请生成如下格式的 SKILL.md（YAML frontmatter + Markdown 步骤说明）：
            
            ---
            name: %s
            description: [一句话描述这个技能做什么]
            when_to_use: [何时应该使用这个技能]
            ---
            
            ## 步骤
            
            [详细的步骤说明，包含具体的命令和参数]
            
            只输出 SKILL.md 的内容，不要包含代码块标记（```）。
            """.formatted(actionsLog.toString(), skillName);

        return chatModel.call(prompt).trim();
    }

    // --- Records ---

    record SkillifySession(String skillName) {
        private static final List<RecordedAction> ACTIONS = new ArrayList<>();
        private static final String STARTED_AT = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME);

        void addAction(RecordedAction action) { ACTIONS.add(action); }
        List<RecordedAction> getActions() { return List.copyOf(ACTIONS); }
    }

    record RecordedAction(String toolName, String params, String result) {}
}
