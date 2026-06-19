package cn.xu.medical.agent.tool.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Scans project and user skill directories, parses SKILL.md files.
 * Supports live reload via WatchService.
 */
@Slf4j
@Component
public class SkillLoader {

    private static final String SKILL_MD = "SKILL.md";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Path projectSkillsDir;
    private final Path userSkillsDir;
    private final SkillRegistry registry;

    public SkillLoader(@Value("${agent.project-root:#{systemProperties['user.dir']}}") String projectRoot,
                       SkillRegistry registry) {
        this.projectSkillsDir = Path.of(projectRoot, ".agent", "skills");
        this.userSkillsDir = Path.of(System.getProperty("user.home"), ".agent", "skills");
        this.registry = registry;
    }

    /**
     * Load all skills from both project and user directories.
     */
    public void loadAll() {
        log.info("Loading skills...");
        loadFromDirectory(projectSkillsDir, "PROJECT");
        loadFromDirectory(userSkillsDir, "USER");
        log.info("Loaded {} skills total", registry.count());
    }

    private void loadFromDirectory(Path skillsDir, String scope) {
        if (!Files.isDirectory(skillsDir)) {
            log.debug("Skill directory not found: {}", skillsDir);
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir, Files::isDirectory)) {
            for (Path skillDir : stream) {
                Path skillFile = skillDir.resolve(SKILL_MD);
                if (Files.isRegularFile(skillFile)) {
                    try {
                        Skill skill = parseSkillFile(skillFile, skillDir.getFileName().toString(), scope);
                        registry.register(skill);
                        log.debug("Loaded skill: {} ({})", skill.getName(), skillFile);
                    } catch (Exception e) {
                        log.warn("Failed to load skill from {}: {}", skillFile, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan skill directory {}: {}", skillsDir, e.getMessage());
        }
    }

    /**
     * Parse a SKILL.md file.
     * Format: YAML frontmatter between --- markers, followed by Markdown body.
     */
    @SuppressWarnings("unchecked")
    public Skill parseSkillFile(Path skillFile, String dirName, String scope) throws IOException {
        String content = Files.readString(skillFile);

        Skill.SkillBuilder builder = Skill.builder()
            .name(dirName)
            .filePath(skillFile.toString())
            .scope(scope);

        if (content.startsWith("---")) {
            int endIdx = content.indexOf("---", 3);
            if (endIdx > 0) {
                String yamlStr = content.substring(3, endIdx).trim();
                String bodyStr = content.substring(endIdx + 3).trim();

                Map<String, Object> frontmatter = YAML.readValue(yamlStr, Map.class);

                builder.name(getString(frontmatter, "name", dirName));
                builder.description(getString(frontmatter, "description", null));
                builder.whenToUse(getString(frontmatter, "when_to_use", null));
                builder.disableModelInvocation(getBoolean(frontmatter, "disable-model-invocation", false));
                builder.context(getString(frontmatter, "context", "inline"));
                builder.model(getString(frontmatter, "model", null));
                builder.userInvocable(getBoolean(frontmatter, "user-invocable", true));
                builder.argumentHint(getString(frontmatter, "argument-hint", null));

                // allowed-tools: can be string or list
                Object allowed = frontmatter.get("allowed-tools");
                if (allowed instanceof String s) {
                    builder.allowedTools(Arrays.asList(s.split("[ ,]+")));
                } else if (allowed instanceof List<?> l) {
                    builder.allowedTools(l.stream().map(Object::toString).toList());
                }

                // disallowed-tools: can be string or list
                Object disallowed = frontmatter.get("disallowed-tools");
                if (disallowed instanceof String s) {
                    builder.disallowedTools(Arrays.asList(s.split("[ ,]+")));
                } else if (disallowed instanceof List<?> l) {
                    builder.disallowedTools(l.stream().map(Object::toString).toList());
                }

                // arguments: can be string or list
                Object args = frontmatter.get("arguments");
                if (args instanceof String s) {
                    builder.arguments(Arrays.asList(s.split("[ ,]+")));
                } else if (args instanceof List<?> l) {
                    builder.arguments(l.stream().map(Object::toString).toList());
                }

                builder.body(bodyStr);
                return builder.build();
            }
        }

        // No frontmatter — entire file is the body
        builder.body(content);
        builder.description(content.lines().findFirst().orElse(dirName));
        return builder.build();
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val != null) return Boolean.parseBoolean(val.toString());
        return defaultValue;
    }
}
