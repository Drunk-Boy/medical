<script setup lang="ts">
import { useAgentStore } from '@/stores/agent'
import { Plus, Delete, ChatDotRound } from '@element-plus/icons-vue'

const agentStore = useAgentStore()

function handleCreate() {
  agentStore.createSession()
}
</script>

<template>
  <div class="session-sidebar">
    <div class="sidebar-header">
      <span class="sidebar-title">会话列表</span>
      <el-button :icon="Plus" circle size="small" @click="handleCreate" />
    </div>
    <div class="session-list">
      <div
        v-for="session in agentStore.sessions"
        :key="session.id"
        class="session-item"
        :class="{ active: session.id === agentStore.currentSessionId }"
        @click="agentStore.currentSessionId = session.id"
      >
        <el-icon><ChatDotRound /></el-icon>
        <span class="session-title">{{ session.title }}</span>
        <el-button
          :icon="Delete"
          circle
          size="small"
          text
          class="delete-btn"
          @click.stop="agentStore.deleteSession(session.id)"
        />
      </div>
      <div v-if="agentStore.sessions.length === 0" class="empty-hint">
        暂无会话，点击 + 新建
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.session-sidebar {
  padding: 16px;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.sidebar-title {
  font-size: 14px;
  font-weight: 600;
  color: #606266;
}

.session-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;

  &:hover { background: #f0f2f5; }
  &.active { background: #ecf5ff; color: #409eff; }

  .session-title {
    flex: 1;
    font-size: 13px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .delete-btn { opacity: 0; transition: opacity 0.2s; }
  &:hover .delete-btn { opacity: 1; }
}

.empty-hint {
  font-size: 12px;
  color: #c0c4cc;
  text-align: center;
  padding: 24px 0;
}
</style>
