# Medical Agent — AI 编程助手

基于 **Spring Boot 4.1.0 + JDK 25 + DeepSeek** 的 AI 编程 Agent，参照 Claude Code 架构设计。

## 项目结构

```
medical/
├── pom.xml                          # 父 POM（多模块）
├── docs/                            # 设计文档
│   ├── architecture.md              #   整体架构
│   ├── permission-model.md          #   七级权限模型
│   ├── tool-system.md               #   工具 + MCP + Skill
│   ├── mcp-design.md                #   MCP 客户端 8 子系统
│   ├── context-pipeline.md          #   五阶段上下文管线
│   └── agent-loop.md                #   Agent 循环 + SSE 契约
├── medical-agent/                   # Agent 核心引擎
│   └── src/main/java/cn/xu/medical/agent/
│       ├── common/                  #   实体、Mapper、配置、DTO
│       ├── permission/              #   七级权限安全管线
│       ├── tool/                    #   工具 + MCP 客户端 + Skill
│       │   ├── builtin/             #     6 个内置工具
│       │   ├── mcp/                 #     MCP 懒加载 + 8 子系统
│       │   └── skill/               #     Skill + /skillify
│       ├── context/                 #   上下文管理 + Agent 循环
│       ├── speculation/             #   投机执行
│       ├── subagent/                #   子代理与并行
│       └── kairos/                  #   始终在线
├── medical-app/                     # Spring Boot 入口
│   └── src/main/resources/
│       ├── db/migration/            #   Flyway 迁移脚本
│       └── application.yml          #   应用配置
└── medical-ui/                      # Vue 3 前端
    └── src/
        ├── components/              #   聊天面板、会话侧栏等
        └── stores/                  #   Pinia 状态管理
```

## 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 运行时 | Java / JDK | 25 |
| 框架 | Spring Boot | 4.1.0 |
| LLM | DeepSeek（Spring AI） | 2.0.0 |
| ORM | MyBatis-Plus | 3.5.13 |
| 数据库 | MySQL | 8.x |
| 缓存 | Redis（Lettuce） | 7.x |
| 迁移 | Flyway | Spring Boot 托管 |
| Git | JGit | 6.x |
| 本地缓存 | Caffeine | Spring Boot 托管 |
| 前端 | Vue 3 + TS + Pinia + Vite | latest |
| UI | Element Plus | latest |

## 前置依赖

- **JDK 25**
- **MySQL 8.x**（默认 `root:root@localhost:3306`）
- **Redis 7.x**（默认 `localhost:6379`）
- **DeepSeek API Key**（[获取](https://platform.deepseek.com/api_keys)）

### Docker 快速启动

```bash
docker run -d --name mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8
docker run -d --name redis -p 6379:6379 redis:7
```

## 快速开始

### 1. 配置

```bash
export DEEPSEEK_API_KEY=sk-your-key-here
export MYSQL_PASSWORD=root        # 默认
```

或修改 `medical-app/src/main/resources/application.yml`。

### 2. 启动后端

```bash
cd medical

# 编译
./mvnw clean package -DskipTests

# 启动（Flyway 自动建表）
./mvnw spring-boot:run -pl medical-app
```

启动后访问 http://localhost:8080

### 3. 启动前端

```bash
cd medical-ui
npm install
npm run dev
```

访问 http://localhost:3000（自动代理 `/api` 到后端 8080）

### 4. 验证

```bash
# 测试 DeepSeek 联通
curl "http://localhost:8080/api/agent/test?message=Hello"

# 创建会话
curl -X POST "http://localhost:8080/api/agent/session?title=Test"
```

## 核心功能

### 七级权限管线

```
用户操作 → [1]GlobalDeny → [2]DirectoryScoped → [3]ExplicitConfirm
  → [4]MLClassifier → [5]SessionAllow → [6]AutoAllow → [7]AlwaysAllow
```

| 级别 | 说明 |
|---|---|
| GlobalDeny | 硬编码禁止（`rm -rf /` 等） |
| DirectoryScoped | 限制在项目目录内 |
| ExplicitConfirm | 高危操作弹窗确认 |
| MLClassifier | DeepSeek 轻量模型判断危险性 |
| SessionAllow | 当前会话内已授权自动放行 |
| AutoAllow | 白名单安全命令 |
| AlwaysAllow | 只读操作无条件放行 |

### 内置工具

| 工具 | 功能 |
|---|---|
| FileReadTool | 分页读取文件 |
| FileWriteTool | 创建/覆盖（自动 Checkpoint） |
| FileEditTool | 精确字符串替换 |
| GlobTool | Glob 模式文件搜索 |
| GrepTool | 正则内容搜索 |
| BashTool | Shell 命令执行（沙箱） |

### MCP 懒加载

启动时仅加载工具名称（极低 Token），Schema 按需获取，缓存 Redis。

### 上下文管理

五阶段管线：结构化裁剪 → Token 预算 → 评分排序 → LLM 摘要压缩 → 可视化

### 投机执行

监听用户输入预测意图，后台预执行任务，结果缓存 Caffeine。

### Skill 系统

- `/skillify <name>` — 从演示中学习，自动生成 SKILL.md
- 支持多级目录发现、热加载、子代理模式

### 子代理

独立上下文 + 受限工具集 + Worktree 隔离 + 并行执行

### KAIROS 始终在线

心跳保活 + 空闲"梦境"后台任务 + 跨会话记忆

## 数据库表

| 表名 | 说明 |
|---|---|
| `a_agent_session` | 会话 |
| `a_agent_message` | 消息 |
| `a_permission_audit` | 权限审计 |
| `a_kairos_memory` | 跨会话记忆 |
| `a_mcp_server_config` | MCP 配置 |
| `a_context_snapshot` | 上下文快照 |
| `a_agent_allow_rule` | 自动允许规则 |
| `a_skill_definition` | Skill 定义 |

## API 端点

| Method | Path | 说明 |
|---|---|---|
| `GET` | `/api/agent/test` | DeepSeek 联通测试 |
| `POST` | `/api/agent/session` | 创建会话 |
| `GET` | `/api/agent/sessions` | 会话列表 |
| `GET` | `/api/agent/{id}/stream` | SSE 流式对话 |
| `GET` | `/api/agent/{id}/context` | 上下文使用率 |
| `POST` | `/api/agent/permission/approve` | 确认高危操作 |
| `DELETE` | `/api/agent/{id}` | 删除会话 |

## 模块依赖

```
medical-app → medical-agent
medical-agent → spring-ai-deepseek, mybatis-plus, redis, jgit, caffeine
medical-ui → Vite proxy → medical-app (localhost:8080)
```
