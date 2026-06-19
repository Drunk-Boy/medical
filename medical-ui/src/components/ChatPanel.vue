<script setup lang="ts">
import { ref, watch, nextTick, computed } from 'vue'
import { useAgentStore } from '@/stores/agent'
import { Promotion } from '@element-plus/icons-vue'

const agentStore = useAgentStore()
const inputText = ref('')
const chatContainer = ref<HTMLElement | null>(null)

const gaugeColor = computed(() => {
  const pct = agentStore.contextUsage.usagePct || 0
  if (pct > 85) return '#f56c6c'
  if (pct > 70) return '#e6a23c'
  return '#67c23a'
})

function handleSend() {
  const text = inputText.value.trim()
  if (!text || agentStore.streaming || !agentStore.currentSessionId) return
  inputText.value = ''
  agentStore.sendMessage(text)
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function formatContent(text: string): string {
  if (!text) return ''
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/```(\w*)\n?([\s\S]*?)```/g, '<pre class="code-block"><code>$2</code></pre>')
    .replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br/>')
}

watch(
  () => agentStore.messages.length + (agentStore.currentAssistantMessage?.length || 0),
  () => nextTick(() => {
    if (chatContainer.value) {
      chatContainer.value.scrollTop = chatContainer.value.scrollHeight
    }
  })
)
</script>

<template>
  <div class="chat-panel">
    <div class="chat-messages" ref="chatContainer">
      <div v-if="!agentStore.currentSessionId" class="empty-state">
        <el-icon :size="48"><Promotion /></el-icon>
        <p>选择或创建一个会话开始对话</p>
      </div>

      <template v-for="msg in agentStore.messages" :key="msg.id">
        <!-- User message -->
        <div v-if="msg.role === 'user'" class="message user-message">
          <div class="msg-bubble user-bubble">{{ msg.content }}</div>
        </div>

        <!-- Tool call -->
        <div v-else-if="msg.role === 'tool_call'" class="message tool-message">
          <div class="msg-bubble tool-bubble">
            <span class="tool-label">🔧 {{ msg.toolName }}</span>
          </div>
        </div>

        <!-- Tool result -->
        <div v-else-if="msg.role === 'tool_result'" class="message tool-message">
          <div class="msg-bubble tool-result-bubble">
            <pre class="tool-output">{{ msg.content }}</pre>
          </div>
        </div>

        <!-- Assistant message -->
        <div v-else-if="msg.role === 'assistant'" class="message assistant-message">
          <div class="msg-bubble assistant-bubble" v-html="formatContent(msg.content)" />
        </div>
      </template>

      <!-- Streaming assistant message -->
      <div v-if="agentStore.streaming && agentStore.currentAssistantMessage" class="message assistant-message">
        <div class="msg-bubble assistant-bubble" v-html="formatContent(agentStore.currentAssistantMessage)" />
        <span class="streaming-indicator">●</span>
      </div>
    </div>

    <div class="chat-input-area">
      <div class="input-wrapper">
        <el-input
          v-model="inputText"
          type="textarea"
          :rows="2"
          placeholder="输入消息... (Enter 发送, Shift+Enter 换行)"
          :disabled="!agentStore.currentSessionId || agentStore.streaming"
          @keydown="handleKeydown"
          resize="none"
        />
        <el-button
          type="primary"
          :disabled="!inputText.trim() || agentStore.streaming"
          @click="handleSend"
          class="send-btn"
        >
          发送
        </el-button>
      </div>
      <div v-if="agentStore.streaming" class="streaming-hint">Agent 正在思考中...</div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.chat-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #c0c4cc;
  gap: 12px;
  p { font-size: 14px; }
}

.message {
  display: flex;
  max-width: 85%;
}

.user-message { align-self: flex-end; }
.assistant-message { align-self: flex-start; }
.tool-message { align-self: flex-start; }

.msg-bubble {
  padding: 10px 16px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.user-bubble {
  background: #409eff;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.assistant-bubble {
  background: #fff;
  color: #303133;
  border: 1px solid #e4e7ed;
  border-bottom-left-radius: 4px;
}

.tool-bubble {
  background: #fdf6ec;
  color: #e6a23c;
  font-size: 13px;
  border: 1px solid #faecd8;
}

.tool-result-bubble {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  font-size: 12px;
  max-width: 100%;
  overflow-x: auto;
}

.tool-label { font-weight: 500; }

.tool-output {
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  font-size: 12px;
  white-space: pre-wrap;
  max-height: 200px;
  overflow-y: auto;
}

.streaming-indicator {
  margin-left: 8px;
  color: #409eff;
  animation: blink 1s infinite;
  align-self: flex-end;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.chat-input-area {
  padding: 16px 24px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}

.input-wrapper {
  display: flex;
  gap: 12px;
  align-items: flex-end;

  :deep(.el-textarea__inner) {
    font-size: 14px;
    line-height: 1.5;
  }
}

.send-btn {
  height: 44px;
}

.streaming-hint {
  font-size: 12px;
  color: #409eff;
  margin-top: 6px;
  text-align: right;
}
</style>
