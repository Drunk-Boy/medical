<script setup lang="ts">
import { onMounted } from 'vue'
import { useAgentStore } from './stores/agent'
import ChatPanel from './components/ChatPanel.vue'
import SessionSidebar from './components/SessionSidebar.vue'
import ContextGauge from './components/ContextGauge.vue'
import PermissionDialog from './components/PermissionDialog.vue'

const agentStore = useAgentStore()

onMounted(() => {
  agentStore.loadSessions()
})
</script>

<template>
  <div class="app-container">
    <aside class="sidebar">
      <SessionSidebar />
    </aside>
    <main class="main-content">
      <header class="top-bar">
        <h1 class="app-title">Medical Agent</h1>
        <ContextGauge />
      </header>
      <ChatPanel />
    </main>
    <PermissionDialog />
  </div>
</template>

<style lang="scss">
* { margin: 0; padding: 0; box-sizing: border-box; }

.app-container {
  display: flex;
  height: 100vh;
  background: #f5f7fa;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
}

.sidebar {
  width: 280px;
  min-width: 280px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.app-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}
</style>
