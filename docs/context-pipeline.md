# 上下文管理 — 五阶段防坍塌管线

## 问题背景

Agent 长对话中，消息历史、工具调用结果、文件内容等不断积累，超过模型上下文窗口上限（DeepSeek: 128K tokens），导致：
- 最早的指令和上下文被截断
- 关键代码片段丢失
- Agent 行为退化，重复错误

## 五阶段管线

每次对话轮次开始前，`ContextManager` 按顺序执行以下阶段：

```
∅ (无动作)
  │ token < 70%
  ▼
[Phase 1] 结构化裁剪 ─── 移除冗余中间结果
  │
  ▼
[Phase 2] Token 预算监视 ─── 70% 警告 / 85% 触发后续
  │
  ▼
[Phase 3] 消息重要性评分 ─── 排序，标记低价值消息
  │
  ▼
[Phase 4] 摘要压缩 ─── 调用 LLM 压缩低分消息
  │
  ▼
[Phase 5] 上下文可视化 ─── 实时展示分布
```

---

## Phase 1: 结构化裁剪

### 目标
在不丢失信息的前提下移除冗余内容。

### 裁剪规则

| 移除内容 | 保留内容 |
|---|---|
| 中间 tool_call 的完整 JSON | tool_call 名称 + 参数摘要（≤100 chars） |
| tool_result 中的重复日志输出 | tool_result 关键结论（≤500 chars） |
| 重复的系统提示注入 | 仅保留首条系统提示 |
| 空白的 assistant 消息 | — |
| 重复的连续相同工具调用 | 合并为 "连续 N 次调用 XX 工具" |

### 实现

```java
public List<Message> structuralPrune(List<Message> messages) {
    return messages.stream()
        .map(this::pruneMessage)
        .filter(m -> m != null)
        .collect(Collectors.toList());
}

private Message pruneMessage(Message msg) {
    if (msg instanceof ToolCallMessage tcm && isIntermediate(tcm)) {
        // 仅保留工具名 + 参数摘要
        return ToolCallSummaryMessage.of(tcm.getToolName(), summarize(tcm.getParams()));
    }
    if (msg instanceof ToolResultMessage trm && trm.getContent().length() > 500) {
        return trm.withContent(trm.getContent().substring(0, 500) + "...");
    }
    return msg;
}
```

---

## Phase 2: Token 预算监视

### Token 估算公式

```java
public int estimateTokenCount(String text) {
    // 粗略估算：中文 ≈ 字数×1.5，英文 ≈ 字符/4
    int chineseChars = countChinese(text);
    int otherChars = text.length() - chineseChars;
    return (int)(chineseChars * 1.5 + otherChars / 4.0);
}
```

### 阈值

| 使用率 | 状态 | 动作 |
|---|---|---|
| <70% | 🟢 正常 | 无动作 |
| 70-85% | 🟡 警告 | 推送前端 ContextGauge 黄色，提示用户考虑 /compact |
| 85-95% | 🟠 压缩 | 自动触发 Phase 3+4 |
| >95% | 🔴 紧急 | 强制压缩，暂停接受新的大文件读取 |

### 实时监控
- 每次消息追加后重新估算
- 当前使用率写入 Redis `agent:session:{id}:context_usage`
- 前端每 3s 轮询 `/api/agent/context` 刷新进度条

---

## Phase 3: 消息重要性评分

### 评分规则

| 消息类型 | 基础权重 | 加分条件 |
|---|---|---|
| 用户消息 | 1.0 | 含 @文件引用 +0.2 |
| Assistant 文本回复 | 0.8 | 含代码块 +0.1 |
| 工具调用（写操作） | 0.7 | 产生错误 +0.3 |
| 工具调用（读操作） | 0.4 | — |
| 工具结果（错误栈） | 0.9 | 匹配已知 bug 模式 +0.2 |
| 工具结果（正常输出） | 0.3 | — |
| 纯日志/调试输出 | 0.1 | — |
| System 提示 | 1.0 (保护) | 关键系统消息标记 PROTECTED |
| SummaryMessage | 0.6 | — |

### 算法

```java
public List<ScoredMessage> scoreMessages(List<Message> messages) {
    return messages.stream()
        .map(m -> new ScoredMessage(m, calculateScore(m)))
        .sorted(Comparator.comparingDouble(ScoredMessage::score))
        .collect(Collectors.toList());
}
```

评分后按分数排序，最低分的消息优先被压缩。

---

## Phase 4: 摘要压缩

### 触发条件
- Token 使用率 >85%
- 存在未被 PROTECTED 标记的低分消息

### 压缩策略

```java
public List<Message> compact(List<ScoredMessage> scoredMessages, int targetTokenReduction) {
    // 1. 选出最低分的 N 条消息，使其删除后能释放 targetTokenReduction 的 Token
    List<ScoredMessage> toCompress = selectLowestScore(scoredMessages, targetTokenReduction);

    // 2. 将这些消息合并为一个压缩 Prompt
    String compactPrompt = buildCompactPrompt(toCompress);

    // 3. 调用 DeepSeek(temperature=0.2, max_tokens=1024) 批量摘要
    String summary = deepSeekChatModel.call(compactPrompt);

    // 4. 用 SummaryMessage 替换原始低分消息
    return replaceWithSummary(scoredMessages, toCompress, summary);
}
```

### 压缩 Prompt 模板

```
请将以下 Agent 对话历史中的关键信息提取为简洁摘要。保留：
- 用户的核心需求
- 已完成的修改（文件路径 + 改动描述）
- 尚未解决的问题
- 重要错误信息

对话历史：
{lowScoreMessages}
```

### 压缩后上下文分布

压缩后消息列表变为：
```
[System Prompt] [用户消息1] [用户消息2] [SummaryMessage(代替3条工具调用)] [用户消息3] ...
```
SummaryMessage 带元数据：来源消息数、原始 Token 数、压缩后 Token 数。

---

## Phase 5: 上下文可视化

### API

`GET /api/agent/{sessionId}/context`

```json
{
  "totalTokens": 85000,
  "maxTokens": 128000,
  "usagePercent": 66.4,
  "breakdown": {
    "systemPrompt": 3200,
    "userMessages": 12000,
    "assistantMessages": 18000,
    "toolCalls": 8000,
    "toolResults": 35000,
    "summaries": 5000,
    "filesInContext": 3800
  },
  "history": [
    {"time": "14:30:00", "tokens": 65000},
    {"time": "14:35:00", "tokens": 72000},
    {"time": "14:40:00", "tokens": 85000}
  ]
}
```

### 前端 ContextGauge 组件

- Element Plus `el-progress` 环形进度条，颜色随使用率变化
- 鼠标悬浮显示各分类占比饼图（ECharts 迷你图）
- 点击展开详细消息列表，标注每条消息的 Token 估算
