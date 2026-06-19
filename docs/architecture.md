# AI Coding Agent — 整体架构设计

## 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 运行时 | Java / JDK | 25 |
| 框架 | Spring Boot | 4.1.0 |
| LLM | DeepSeek (via Spring AI) | spring-ai 2.0.0 |
| ORM | MyBatis-Plus | 3.5.x |
| 数据库 | MySQL | 8.x |
| 缓存 | Redis (Lettuce) | 7.x |
| DB迁移 | Flyway | 10.x |
| Git操作 | JGit | 6.x |
| 本地缓存 | Caffeine | 3.x |
| 前端 | Vue 3 + TS + Pinia + Vite + Element Plus | latest |
| 构建 | Maven (multi-module) | 4.x |

## 模块结构

```
medical/                           ← 父 POM (packaging=pom)
├── pom.xml
├── src/main/resources/
│   └── db/migration/              ← Flyway 脚本
├── medical-agent/                 ← Agent 核心引擎
│   └── src/main/java/cn/xu/medical/agent/
│       ├── permission/            ← 七级权限安全管线
│       ├── tool/                  ← 工具 + MCP 客户端 + Skill
│       │   ├── builtin/           ←   内置工具
│       │   ├── mcp/               ←   MCP 客户端(8子系统)
│       │   └── skill/             ←   Skill 系统
│       ├── context/               ← 上下文管理
│       ├── speculation/           ← 投机执行
│       ├── subagent/              ← 子代理与并行
│       └── kairos/                ← 始终在线
└── medical-ui/                    ← Vue 3 前端(独立npm项目)
```

## 数据流

```
用户输入(Vue) → SSE/HTTP → Spring Boot Controller
  → AgentLoopService
    → SystemPromptBuilder(组装上下文)
    → ChatClient(DeepSeek) + ToolCallingAdvisor
      → [模型请求工具调用]
        → PermissionChain.evaluate()
        → Tool.execute()
        → 结果返回模型
    → Flux<AgentEvent> → SSE → 前端实时渲染
```

## 核心设计决策

1. **ChatClient + ToolCallingAdvisor**：由 Spring AI 自动管理工具调用循环，无需手写 while 循环
2. **MCP 懒加载**：启动仅加载工具名，Schema 按需获取，大幅节省 Token
3. **Redis 双层缓存**：热点数据(会话/权限)用 Redis，投机结果用 Caffeine 本地缓存
4. **MyBatis-Plus**：会话/消息/审计/配置等全部持久化，利用代码生成器提速
5. **SSE 流式输出**：Agent 思考和工具调用过程实时推送到前端
