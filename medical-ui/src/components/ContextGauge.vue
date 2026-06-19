<script setup lang="ts">
import { onMounted, onUnmounted, computed } from 'vue'
import { useAgentStore } from '@/stores/agent'

const agentStore = useAgentStore()
let timer: ReturnType<typeof setInterval> | null = null

const gaugeColor = computed(() => {
  const pct = agentStore.contextUsage.usagePct || 0
  if (pct > 85) return '#f56c6c'
  if (pct > 70) return '#e6a23c'
  return '#67c23a'
})

onMounted(() => {
  timer = setInterval(() => {
    if (agentStore.currentSessionId) {
      agentStore.fetchContextUsage()
    }
  }, 5000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="context-gauge">
    <el-tooltip :content="`${agentStore.contextUsage.totalTokens} / ${agentStore.contextUsage.maxTokens} tokens`" placement="bottom">
      <div class="gauge-wrapper">
        <el-progress
          type="dashboard"
          :percentage="Math.round(agentStore.contextUsage.usagePct || 0)"
          :color="gaugeColor"
          :width="60"
          :stroke-width="6"
        />
        <span class="gauge-label">上下文</span>
      </div>
    </el-tooltip>
  </div>
</template>

<style lang="scss" scoped>
.context-gauge {
  display: flex;
  align-items: center;
}

.gauge-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.gauge-label {
  font-size: 11px;
  color: #909399;
}
</style>
