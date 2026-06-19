package cn.xu.medical.agent.tool.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Parsed Skill definition from SKILL.md.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {

    // --- Frontmatter fields ---
    private String name;
    private String description;
    private String whenToUse;
    private boolean disableModelInvocation;
    private List<String> allowedTools;
    private List<String> disallowedTools;
    private String context;          // "inline" or "fork"
    private String model;            // optional model override
    private List<String> arguments;  // positional argument names
    private String argumentHint;     // e.g. "[issue-number] [format]"
    private boolean userInvocable = true;

    // --- Runtime ---
    private String body;             // Markdown body (instructions)
    private String filePath;         // SKILL.md file path
    private String scope;            // PROJECT / USER
    private Map<String, String> substitutions; // resolved $args

    public String getCommandName() {
        return "/" + (name != null ? name : "unnamed");
    }
}
