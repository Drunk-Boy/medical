package cn.xu.medical.agent.permission;

/**
 * Seven-level permission pipeline, ordered by priority (highest first).
 */
public enum PermissionLevel {

    /** Hardcoded forbidden commands — immediate DENY */
    GLOBAL_DENY(1, "全局拒绝"),

    /** Path traversal check — deny if target outside project root */
    DIRECTORY_SCOPED(2, "目录限定"),

    /** High-risk pattern match — requires explicit user confirmation */
    EXPLICIT_CONFIRM(3, "显式确认"),

    /** ML model judges command danger — cache result */
    ML_CLASSIFIER(4, "ML 分类器"),

    /** Previously approved in this session — allow without asking */
    SESSION_ALLOW(5, "会话内允许"),

    /** Matches whitelist — safe commands auto-allowed */
    AUTO_ALLOW(6, "自动允许"),

    /** Read-only tools — always allowed */
    ALWAYS_ALLOW(7, "始终允许");

    private final int priority;
    private final String label;

    PermissionLevel(int priority, String label) {
        this.priority = priority;
        this.label = label;
    }

    public int priority() { return priority; }
    public String label() { return label; }
}
