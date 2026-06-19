# MCP 客户端设计 — 8 个子系统

## 整体架构

```
mcp/
├── McpClientManager        ← 连接管理中枢
├── McpToolLazyLoader       ← 延迟加载（见 tool-system.md）
├── McpOAuthFlow            ← OAuth 2.0 认证
├── McpConfigMerger         ← 三级配置合并
├── ChannelAllowlist        ← Channel 白名单
├── ChannelPermissionRelay  ← Channel 权限中继
├── ElicitationHandler      ← 用户交互引导
├── EnvExpander             ← 环境变量展开
└── HeadersInjector         ← Header 辅助注入
```

---

## 1. McpClientManager — 连接管理中枢

### 职责
- 管理所有 MCP 服务器连接的生命周期（connect / disconnect / reconnect）
- 支持三种传输协议：STDIO、SSE、Streamable-HTTP
- 心跳保活（ping/ack 间隔 30s）
- 自动重连（指数退避：1s → 2s → 4s → 8s → 16s，最多 5 次）
- 连接状态推送 Redis Pub/Sub → 前端实时展示

### 传输抽象

```java
public interface McpTransport {
    Mono<Void> connect(McpServerConfig config);
    Mono<Void> disconnect();
    Flux<McpMessage> receive();
    Mono<Void> send(McpMessage message);
    TransportType type(); // STDIO, SSE, STREAMABLE_HTTP
    boolean isConnected();
}
```

### 连接池
- 每个 MCP 服务器一个长连接
- 连接状态存入 Redis Hash `mcp:connections:{serverName}`

---

## 2. McpOAuthFlow — OAuth 2.0 认证流程

### 支持的流程
- **授权码流程（Authorization Code + PKCE）**：远程 HTTP/SSE 服务器
- **令牌刷新（Refresh Token）**：令牌过期前 5 分钟自动刷新
- **客户端凭证（Client Credentials）**：服务间认证

### 凭证存储
- AES-256-GCM 加密
- 加密后存入 Redis：`mcp:oauth:{serverName}:credentials`
- 内存中仅保留解密后的 access_token，refresh_token 用后即焚

### 时序

```
Client                          MCP Server                  Redis
  │                                 │                          │
  │── GET /authorize?pkce──>        │                          │
  │<── redirect to callback ──      │                          │
  │                                 │                          │
  │── POST /token ────────────>     │                          │
  │<── access_token + refresh ──    │                          │
  │                                 │                          │
  │── store encrypted ─────────────────────────────────────>  │
  │                                 │                          │
  │  [接近过期]                      │                          │
  │── POST /token(refresh) ────>    │                          │
  │<── new access_token ──────      │                          │
```

---

## 3. McpConfigMerger — 三级配置合并

### 配置层级（后者覆盖前者）

```
Level 1: 托管配置 (Server Managed)     ← 最低优先级，组织管理员下发
Level 2: 项目配置 (.mcp.json)          ← 项目根目录，纳入版本控制
Level 3: 用户配置 (~/.mcp.json)        ← 最高优先级，用户个人覆盖
```

### 合并策略

```java
public McpServerConfig merge(List<McpServerConfig> configs) {
    McpServerConfig result = new McpServerConfig();
    for (McpServerConfig config : configs) {
        // 字段级覆盖：非 null 值覆盖前一层
        if (config.getCommand() != null) result.setCommand(config.getCommand());
        if (config.getArgs() != null) result.setArgs(mergeArrays(result.getArgs(), config.getArgs()));
        if (config.getEnv() != null) result.getEnv().putAll(config.getEnv());
        if (config.getHeaders() != null) result.getHeaders().putAll(config.getHeaders());
        // ...
    }
    return result;
}
```

- **标量字段**：后者非 null 即覆盖
- **数组字段**：并集去重（工具白名单等）
- **Map 字段**：后者覆盖同 Key，新增不同 Key

---

## 4. ChannelAllowlist — Channel 白名单

### 职责
- 限制哪些 MCP Channel 可以向 Agent 推送消息
- 支持通配符匹配：`github:*`、`slack:channel-*`
- 配置热加载：监听 `.mcp.json` 变更 + Redis Pub/Sub 通知

### 匹配逻辑

```java
public boolean isAllowed(String channelId, String senderId) {
    // 1. 精确匹配 channelId
    // 2. 通配符匹配 channelId
    // 3. 检查 senderId 是否在允许列表
    return allowlist.stream().anyMatch(rule -> rule.matches(channelId, senderId));
}
```

---

## 5. ChannelPermissionRelay — Channel 权限中继

### 职责
- Channel 中的 MCP 工具调用同样需要经过七级权限管线
- 将 Channel 请求"中继"到主 Agent 的 `PermissionChain`
- 用户不在线时：高危操作 → 排队等待用户下次上线确认
- 用户在线时：通过 SSE 推送确认弹窗

---

## 6. ElicitationHandler — 用户交互引导

### 职责
- 处理 MCP 服务器的 `elicitation` 请求（要求用户提供信息）
- 生成交互卡片 → SSE 推送前端 → 收集响应 → 回传 MCP 服务器

### 支持的交互类型
- **文本输入**：自由文本（如 "请输入数据库密码"）
- **单选**：选项列表（如 "选择环境：dev / staging / prod"）
- **确认**：是/否（如 "确认删除？"）

---

## 7. EnvExpander — 环境变量展开

### 展开优先级

```
${VAR} 解析顺序:
  1. System.getenv("VAR")        ← 系统环境变量
  2. .env 文件                    ← 项目根目录
  3. Redis mcp:secrets:{VAR}     ← 密钥库
  4. 默认值 ${VAR:-default}
```

### 示例
```
配置: "url": "https://${API_HOST:-api.example.com}/mcp"
  → API_HOST=prod.api.com → https://prod.api.com/mcp
  → API_HOST 未设置      → https://api.example.com/mcp
```

---

## 8. HeadersInjector — Header 辅助注入

### 自动注入的 Header

| Header | 来源 | 说明 |
|---|---|---|
| `X-Request-Id` | UUID | 请求追踪 |
| `X-Session-Id` | AgentSession.id | 会话关联 |
| `X-Agent-Version` | application.version | Agent 版本 |
| `Authorization` | OAuth token | Bearer 令牌（如有） |
| 自定义 | 配置文件 | `headers` 字段模板 |

### 模板化配置

```json
{
  "headers": {
    "X-Custom": "{{session.userId}}",
    "X-Project": "{{project.name}}"
  }
}
```
