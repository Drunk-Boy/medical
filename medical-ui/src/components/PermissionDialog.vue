<script setup lang="ts">
import { computed } from 'vue'
import { usePermissionStore } from '@/stores/agent'

const permissionStore = usePermissionStore()

const showDialog = computed(() => permissionStore.pendingConfirmations.length > 0)
</script>

<template>
  <el-dialog
    v-model="showDialog"
    title="⚠️ 权限确认"
    width="500px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
  >
    <div v-for="(pending, index) in permissionStore.pendingConfirmations" :key="index" class="confirm-item">
      <el-alert type="warning" :closable="false" show-icon>
        <template #title>
          <div class="confirm-title">{{ pending.toolName }} 需要确认</div>
        </template>
        <div class="confirm-body">
          <pre class="confirm-command">{{ pending.content || pending.confirmPrompt }}</pre>
        </div>
      </el-alert>
      <div class="confirm-actions">
        <el-button type="danger" @click="permissionStore.deny(pending.confirmToken)">
          拒绝
        </el-button>
        <el-button type="primary" @click="permissionStore.approve(pending.confirmToken)">
          允许执行
        </el-button>
      </div>
    </div>
  </el-dialog>
</template>

<style lang="scss" scoped>
.confirm-item {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.confirm-title {
  font-weight: 600;
  font-size: 15px;
}

.confirm-body {
  margin-top: 8px;
}

.confirm-command {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 6px;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
}

.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 8px;
}
</style>
