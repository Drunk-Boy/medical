# 七级权限安全模型

## 决策管线

权限检查按优先级从高到低依次执行，一旦命中即返回决策，不再继续：

```
用户操作请求
  │
  ▼
[1] GlobalDenyRule        ← 硬编码禁止列表，命中→DENY
  │ 未命中
  ▼
[2] DirectoryScopedGate   ← 路径越界检测，越界→DENY
  │ 通过
  ▼
[3] ExplicitConfirmGate   ← 高危模式匹配，命中→NEED_CONFIRM
  │ 未命中
  ▼
[4] MlClassifierGate      ← DeepSeek 轻量模型判断，危险→NEED_CONFIRM
  │ 安全
  ▼
[5] SessionAllowCache     ← Redis 查当前会话已授权列表，命中→ALLOW
  │ 未命中
  ▼
[6] AutoAllowRule         ← 白名单匹配(安全命令)，命中→ALLOW
  │ 未命中
  ▼
[7] AlwaysAllowRule       ← 只读工具(Read/Glob/Grep)，命中→ALLOW
  │ 未命中
  ▼
NEED_CONFIRM (默认保守)
```

## 各级定义

### 1. GlobalDenyRule（全局拒绝）
- **触发条件**：操作匹配硬编码禁止列表
- **禁止列表示例**：
  - `rm -rf /`、`rm -rf /*`
  - `fork bomb` 模式 (`:(){ :|:& };:`)
  - `curl ... | bash` (无校验管道执行)
  - `chmod 777 /` (全局权限开放)
  - `> /dev/sda` (直接写块设备)
  - 已知恶意脚本/命令
- **返回**：`PermissionDecision.DENY` + 原因

### 2. DirectoryScopedGate（目录限定）
- **触发条件**：操作目标路径不在项目根目录白名单内
- **校验逻辑**：
  - 解析所有文件/目录操作参数中的路径
  - `Paths.get(target).toRealPath().startsWith(projectRoot.toRealPath())`
  - 符号链接追踪到真实路径后再判定
- **返回**：越界 → `DENY`，通过 → 继续

### 3. ExplicitConfirmGate（显式确认）
- **触发条件**：操作匹配高危模式正则表
- **高危模式**：
  - `rm\s+-rf?\s`（强制删除）
  - `sudo\s+`（提权）
  - `chmod\s+[0-7]*7`（开放权限）
  - `git push --force`（强制推送）
  - `DROP\s+(TABLE|DATABASE)`（删库）
  - `DELETE\s+FROM.*WHERE`（无 WHERE 或全表删除检测）
  - 外部网络请求（URL 含非白名单域名）
- **流程**：生成确认令牌 → Redis 暂存 → SSE 推送前端弹窗 → 等待用户 Approve/Deny
- **超时**：60s 无响应 → 自动 DENY

### 4. MlClassifierGate（ML 分类器）
- **实现**：调用 DeepSeek（model=deepseek-chat, temperature=0）
- **输入**：完整命令字符串 + 上下文（当前目录、项目类型）
- **Prompt**：
  ```
  判断以下 shell 命令是否危险。仅回复 SAFE 或 DANGEROUS。
  危险标准：删除文件、修改系统配置、访问敏感路径、网络数据外发。
  命令：{command}
  ```
- **缓存**：相同命令+上下文哈希 → Redis TTL 10min

### 5. SessionAllowCache（会话内允许）
- **存储**：Redis Hash `agent:session:{sessionId}:allowed`
- **Key**：`{toolName}:{paramHash}`
- **有效期**：会话结束清空
- **触发**：用户曾在该会话中 Approve 过相同操作

### 6. AutoAllowRule（自动允许）
- **配置源**：`application.yml` 白名单 + DB `agent_allow_rules` 表
- **匹配方式**：命令前缀匹配 + 通配符
- **默认白名单**：
  - `git status`, `git log`, `git diff`, `git branch`
  - `mvn test`, `mvn compile`, `mvn clean`
  - `ls`, `cat`, `head`, `tail`, `wc`, `find`
  - `echo`, `date`, `which`, `type`

### 7. AlwaysAllowRule（始终允许）
- **仅适用工具**：FileReadTool, GlobTool, GrepTool, LspTool
- **无条件放行**：只读操作不修改系统状态

## 审计日志

每次权限决策写入 `permission_audit` 表：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| session_id | VARCHAR(64) | 会话 ID |
| tool_name | VARCHAR(64) | 工具名称 |
| tool_params | TEXT | 工具参数 JSON |
| decision_level | VARCHAR(32) | 命中级别 |
| decision | VARCHAR(16) | ALLOW/DENY/NEED_CONFIRM |
| reason | VARCHAR(512) | 决策原因 |
| user_response | VARCHAR(16) | 用户响应（Approve/Deny/Timeout） |
| created_at | DATETIME | 创建时间 |
