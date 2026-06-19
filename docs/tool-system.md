# 工具系统设计 — 内置工具 + MCP 懒加载 + Skill

## 一、整体架构

```
tool/
├── ToolRegistry          ← 统一注册表(ConcurrentHashMap)
├── ToolResult            ← 统一结果封装
├── builtin/              ← 内置工具
│   ├── FileReadTool
│   ├── FileWriteTool
│   ├── FileEditTool
│   ├── GlobTool
│   ├── GrepTool
│   └── BashTool
├── mcp/                  ← MCP 客户端(8子系统)
│   ├── McpClientManager
│   ├── McpToolLazyLoader  ← ★懒加载核心
│   ├── McpOAuthFlow
│   ├── McpConfigMerger
│   ├── ChannelAllowlist
│   ├── ChannelPermissionRelay
│   ├── ElicitationHandler
│   ├── EnvExpander
│   └── HeadersInjector
└── skill/                ← Skill 系统
    ├── SkillLoader
    ├── SkillRegistry
    ├── SkillExecutor
    └── SkillifyCommand
```

## 二、内置工具规范

所有内置工具通过 `@Tool` 注解定义，由 `ToolRegistry` 扫描注册：

```java
@Component
public class FileReadTool {
    @Tool(description = "读取文件内容，支持分页。offset: 起始行(0-based)，limit: 最大行数")
    public ToolResult readFile(
        @ToolParam(description = "文件路径") String path,
        @ToolParam(description = "起始行偏移", required = false) Integer offset,
        @ToolParam(description = "最大读取行数", required = false) Integer limit) {
        // 1. PermissionChain.evaluate()
        // 2. 文件大小检查(>1MB 提示截断)
        // 3. 读取并返回
    }
}
```

### 六类内置工具

| 工具 | 核心能力 | 限制 |
|---|---|---|
| FileReadTool | 分页读取文件，支持 offset/limit | 单次最大 2000 行，>1MB 截断 |
| FileWriteTool | 创建/覆盖文件 | 写入前自动 Checkpoint |
| FileEditTool | 精确字符串替换(old_str→new_str) | old_str 必须在文件中唯一 |
| GlobTool | 递归 glob 模式匹配文件 | 自动过滤 .gitignore |
| GrepTool | 正则搜索文件内容 | 结果上限 200 条 |
| BashTool | 执行 shell 命令 | 30s 超时，输出截断 10KB |

## 三、MCP 懒加载（核心优化）

### 问题
传统模式：启动时加载所有 MCP 工具的完整 JSON Schema，50+ 工具可消耗 55000+ Token。

### 解决方案
```
启动阶段：
  MCP 服务器连接 → 仅拉取工具名称列表 → 存入 ToolIndex(名+一句话描述)
  → 约 50 Token/工具 → 50 工具仅 ~2500 Token

运行时（首次调用工具X）：
  模型调用 toolSearch("X 的功能描述") → ToolIndex 匹配到工具 X
  → McpToolLazyLoader 向 MCP 服务器请求工具 X 的完整 Schema
  → Schema 缓存 Redis(TTL 30min) + 注入当前会话上下文
  → 模型获得完整参数信息，正式调用工具 X

二次调用（同会话）：
  Schema 已在上下文中 → 直接调用，无需重新加载
```

### 关键实现

```java
public class McpToolLazyLoader {
    // 阶段1：仅加载工具名称(启动时)
    public List<ToolReference> loadToolNames(McpServerConnection conn) {
        // 调用 MCP tools/list → 仅提取 name + description 首句
        // 不拉取 inputSchema
    }

    // 阶段2：按需加载完整 Schema(模型首次调用时)
    public ToolDefinition loadFullSchema(String serverName, String toolName) {
        // 检查 Redis 缓存 → 命中返回
        // 未命中 → MCP tools/call 获取完整 inputSchema → 缓存 Redis
    }
}
```

### 与 Spring AI ToolSearch 集成
- `McpToolLazyLoader` 实现 `ToolIndex` 接口
- 注册为 `ToolSearchToolCallingAdvisor` 的索引源
- 模型自动通过 `toolSearchTool` 发现和加载所需工具

## 四、Skill 系统

### 目录结构（对标 Claude Code）

```
~/.agent/skills/              ← 用户级(跨项目)
.agent/skills/                ← 项目级(版本控制)
  └── deploy/
      ├── SKILL.md            ← 入口(必须)
      ├── template.md         ← 模板
      └── scripts/
          └── validate.sh     ← 辅助脚本
```

### SKILL.md 格式

```markdown
---
name: deploy
description: 部署应用到生产环境
when_to_use: 用户要求部署或发布时
disable-model-invocation: true
allowed-tools: Bash, Read, Grep
context: fork
---

## 部署步骤

1. 运行测试: `mvn test`
2. 构建项目: `mvn package -DskipTests`
3. 检查当前分支: `!`git branch --show-current``
4. 部署: ...
```

### Skill 生命周期

```
SKILL.md 文件变更
  → WatchService 检测
  → SkillLoader 重新解析
  → SkillRegistry 热更新(name → Skill)
  → 下次对话轮次生效(无需重启)
```

### /skillify 命令流程

```
用户: /skillify deploy
Agent: "请演示部署步骤，完成后输入 /end-skillify"
用户: [执行 mvn test] [执行 mvn package] ...
用户: /end-skillify
Agent:
  1. 收集演示期间的所有工具调用记录
  2. 调用 DeepSeek 摘要生成步骤说明
  3. 自动生成 SKILL.md(YAML frontmatter + 步骤列表)
  4. 写入 .agent/skills/deploy/SKILL.md
  5. SkillRegistry 热加载新 Skill
```

### Skill 执行模式

| 模式 | context 值 | 行为 |
|---|---|---|
| 内联 | inline(默认) | Skill 内容注入当前系统提示，共享 ChatMemory |
| 子代理 | fork | 独立 ChatClient + 独立 ChatMemory，完成后返回摘要 |
