-- =====================================================
-- V1__init_schema.sql
-- AI Coding Agent — Initial Schema (MySQL)
-- =====================================================

CREATE TABLE IF NOT EXISTS a_agent_session (
    id          VARCHAR(64)  PRIMARY KEY COMMENT '会话ID',
    title       VARCHAR(256) NOT NULL DEFAULT 'New Session' COMMENT '会话标题',
    project_dir VARCHAR(512) COMMENT '项目根目录',
    model_name  VARCHAR(64)  DEFAULT 'deepseek-chat' COMMENT '模型',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 会话';

CREATE TABLE IF NOT EXISTS a_agent_message (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    session_id    VARCHAR(64)  NOT NULL COMMENT '会话ID',
    role          VARCHAR(32)  NOT NULL COMMENT '角色',
    content       MEDIUMTEXT   COMMENT '内容',
    metadata_json JSON         COMMENT '元数据',
    token_count   INT          DEFAULT 0 COMMENT 'Token数',
    seq           INT          NOT NULL COMMENT '序号',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_msg_session_seq (session_id, seq),
    CONSTRAINT fk_msg_session FOREIGN KEY (session_id) REFERENCES a_agent_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 消息';

CREATE TABLE IF NOT EXISTS a_permission_audit (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    session_id     VARCHAR(64)  COMMENT '会话ID',
    tool_name      VARCHAR(64)  NOT NULL COMMENT '工具名',
    tool_params    TEXT         COMMENT '参数JSON',
    decision_level VARCHAR(32)  NOT NULL COMMENT '命中级别',
    decision       VARCHAR(16)  NOT NULL COMMENT 'ALLOW/DENY/NEED_CONFIRM',
    reason         VARCHAR(512) COMMENT '原因',
    user_response  VARCHAR(16)  COMMENT '用户响应',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限审计';

CREATE TABLE IF NOT EXISTS a_kairos_memory (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    scope       VARCHAR(16)  NOT NULL DEFAULT 'PROJECT' COMMENT '作用域',
    scope_key   VARCHAR(128) NOT NULL COMMENT '作用域键',
    memory_key  VARCHAR(128) NOT NULL COMMENT '记忆键',
    content     MEDIUMTEXT   NOT NULL COMMENT '内容',
    tags        VARCHAR(512) COMMENT '标签',
    importance  INT          DEFAULT 0 COMMENT '重要性',
    access_count BIGINT      DEFAULT 0 COMMENT '访问次数',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_memory (scope, scope_key, memory_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='KAIROS 记忆';

CREATE TABLE IF NOT EXISTS a_mcp_server_config (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(128) NOT NULL COMMENT '服务器名',
    config_json   JSON         NOT NULL COMMENT '配置JSON',
    scope         VARCHAR(16)  NOT NULL DEFAULT 'PROJECT' COMMENT '作用域',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    status        VARCHAR(16)  DEFAULT 'DISCONNECTED' COMMENT '连接状态',
    tool_count    INT          DEFAULT 0 COMMENT '工具数',
    last_connected_at DATETIME,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_mcp_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 配置';

CREATE TABLE IF NOT EXISTS a_context_snapshot (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    session_id   VARCHAR(64)  NOT NULL,
    total_tokens INT          NOT NULL COMMENT '总Token',
    max_tokens   INT          NOT NULL DEFAULT 128000,
    usage_pct    DECIMAL(5,2) NOT NULL COMMENT '使用率%',
    breakdown_json JSON       COMMENT '分布',
    compacted    TINYINT(1)   DEFAULT 0,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ctx_session (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上下文快照';

CREATE TABLE IF NOT EXISTS a_agent_allow_rule (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    pattern     VARCHAR(256) NOT NULL COMMENT '匹配模式',
    tool_name   VARCHAR(64)  COMMENT '工具名',
    priority    INT          DEFAULT 0 COMMENT '优先级',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动允许规则';

CREATE TABLE IF NOT EXISTS a_skill_definition (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128) NOT NULL COMMENT 'Skill名',
    scope       VARCHAR(16)  NOT NULL DEFAULT 'PROJECT' COMMENT '作用域',
    file_path   VARCHAR(512) NOT NULL COMMENT '文件路径',
    frontmatter_json JSON    COMMENT 'Frontmatter',
    body_md     MEDIUMTEXT   COMMENT 'Markdown正文',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_skill_name_scope (name, scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill 定义';
