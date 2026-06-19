package cn.xu.medical.agent.tool.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of all loaded skills.
 * Supports hot-reload: add/update/remove skills at runtime.
 */
@Slf4j
@Component
public class SkillRegistry {

    /** key = skill name, value = Skill */
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    public void register(Skill skill) {
        skills.put(skill.getName(), skill);
        log.debug("Registered skill: {}", skill.getCommandName());
    }

    public void unregister(String name) {
        skills.remove(name);
    }

    public Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public Skill getOrThrow(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            throw new NoSuchElementException("Skill not found: " + name);
        }
        return skill;
    }

    public List<Skill> getAll() {
        return List.copyOf(skills.values());
    }

    /**
     * Find skills that Claude might auto-invoke (not disable-model-invocation).
     */
    public List<Skill> getAutoInvokableSkills() {
        return skills.values().stream()
            .filter(s -> !s.isDisableModelInvocation())
            .toList();
    }

    /**
     * Get skill descriptions for embedding in system prompt.
     * Only includes skills that the model can auto-invoke.
     */
    public String getSkillListForPrompt() {
        StringBuilder sb = new StringBuilder();
        List<Skill> autoSkills = getAutoInvokableSkills();
        if (!autoSkills.isEmpty()) {
            sb.append("## Available Skills\n\n");
            for (Skill skill : autoSkills) {
                sb.append("- **").append(skill.getCommandName()).append("**: ")
                    .append(skill.getDescription() != null ? skill.getDescription() : "No description")
                    .append("\n");
            }
        }
        return sb.toString();
    }

    public int count() {
        return skills.size();
    }

    public void clear() {
        skills.clear();
    }
}
