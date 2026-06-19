import { defineStore } from 'pinia'
import { ref } from 'vue'
import axios from 'axios'

export interface Message {
  id: string
  role: 'user' | 'assistant' | 'tool_call' | 'tool_result' | 'system'
  content: string
  toolName?: string
  toolCallId?: string
  timestamp: Date
}

export interface AgentSession {
  id: string
  title: string
  modelName: string
  status: string
  createdAt: string
  updatedAt: string
}

export const useAgentStore = defineStore('agent', () => {
  const sessions = ref<AgentSession[]>([])
  const currentSessionId = ref<string | null>(null)
  const messages = ref<Message[]>([])
  const streaming = ref(false)
  const currentAssistantMessage = ref('')
  const contextUsage = ref({ totalTokens: 0, maxTokens: 128000, usagePct: 0 })

  async function loadSessions() {
    const { data } = await axios.get('/api/agent/sessions')
    sessions.value = data
  }

  async function createSession(title: string = 'New Session') {
    const { data } = await axios.post('/api/agent/session', null, { params: { title } })
    sessions.value.unshift(data)
    currentSessionId.value = data.id
    messages.value = []
    return data
  }

  async function deleteSession(sessionId: string) {
    await axios.delete(`/api/agent/${sessionId}`)
    sessions.value = sessions.value.filter(s => s.id !== sessionId)
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = null
      messages.value = []
    }
  }

  async function fetchContextUsage() {
    if (!currentSessionId.value) return
    const { data } = await axios.get(`/api/agent/${currentSessionId.value}/context`)
    contextUsage.value = data
  }

  function sendMessage(content: string) {
    if (!currentSessionId.value || streaming.value) return

    const userMsg: Message = {
      id: 'msg_' + Date.now(),
      role: 'user',
      content,
      timestamp: new Date(),
    }
    messages.value.push(userMsg)
    streaming.value = true
    currentAssistantMessage.value = ''
    const assistantMsgId = 'msg_' + (Date.now() + 1)

    const eventSource = new EventSource(
      `/api/agent/${currentSessionId.value}/stream?message=${encodeURIComponent(content)}`
    )

    eventSource.addEventListener('text_chunk', (e: MessageEvent) => {
      const data = JSON.parse(e.data)
      currentAssistantMessage.value += data.content
    })

    eventSource.addEventListener('tool_call_start', (e: MessageEvent) => {
      const data = JSON.parse(e.data)
      messages.value.push({
        id: 'msg_' + Date.now(),
        role: 'tool_call',
        content: `调用工具: ${data.toolName}`,
        toolName: data.toolName,
        toolCallId: data.callId,
        timestamp: new Date(),
      })
    })

    eventSource.addEventListener('tool_call_result', (e: MessageEvent) => {
      const data = JSON.parse(e.data)
      messages.value.push({
        id: 'msg_' + Date.now(),
        role: 'tool_result',
        content: data.output || data.error || '',
        toolCallId: data.callId,
        timestamp: new Date(),
      })
    })

    eventSource.addEventListener('permission_required', (e: MessageEvent) => {
      const data = JSON.parse(e.data)
      // Trigger permission dialog via store
      permissionStore.addPending(data)
    })

    eventSource.addEventListener('done', () => {
      messages.value.push({
        id: assistantMsgId,
        role: 'assistant',
        content: currentAssistantMessage.value,
        timestamp: new Date(),
      })
      currentAssistantMessage.value = ''
      streaming.value = false
      eventSource.close()
      fetchContextUsage()
    })

    eventSource.addEventListener('error', (e: MessageEvent) => {
      const data = JSON.parse(e.data || '{}')
      messages.value.push({
        id: assistantMsgId,
        role: 'assistant',
        content: `❌ 错误: ${data.errorMessage || '未知错误'}`,
        timestamp: new Date(),
      })
      streaming.value = false
      eventSource.close()
    })
  }

  return {
    sessions, currentSessionId, messages, streaming,
    currentAssistantMessage, contextUsage,
    loadSessions, createSession, deleteSession,
    sendMessage, fetchContextUsage,
  }
})

export const usePermissionStore = defineStore('permission', () => {
  const pendingConfirmations = ref<any[]>([])

  function addPending(data: any) {
    pendingConfirmations.value.push(data)
  }

  async function approve(token: string) {
    await axios.post('/api/agent/permission/approve', null, { params: { token } })
    pendingConfirmations.value = pendingConfirmations.value.filter(p => p.confirmToken !== token)
  }

  async function deny(token: string) {
    await axios.post('/api/agent/permission/deny', null, { params: { token } })
    pendingConfirmations.value = pendingConfirmations.value.filter(p => p.confirmToken !== token)
  }

  return { pendingConfirmations, addPending, approve, deny }
})
