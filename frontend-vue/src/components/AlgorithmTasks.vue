<template>
  <div class="auto-annotation">
    <p class="section-desc">通过 Grounding DINO 检测 + VLM 智能清洗，实现全自动标注流程</p>

    <el-card v-if="project.labels && project.labels.length > 0" class="panel">
      <template #header><span class="card-title">项目信息</span></template>
      <div class="info-row">
        <div class="info-item"><span class="info-label">项目名称</span><span class="info-value">{{ project.name }}</span></div>
        <div class="info-item"><span class="info-label">图片数量</span><span class="info-value">{{ project.totalImages || 0 }}</span></div>
        <div class="info-item"><span class="info-label">已处理图片</span><span class="info-value">{{ project.processedImages || 0 }}</span></div>
        <div class="info-item">
          <span class="info-label">类别</span>
          <div class="labels-inline">
            <el-tag v-for="(label, index) in project.labels" :key="index" size="small" style="margin-right: 6px;">{{ label }}</el-tag>
          </div>
        </div>
      </div>
    </el-card>

    <el-card class="panel">
      <template #header><span class="card-title">操作</span></template>
      <div class="action-center">
        <el-button 
          type="primary" 
          size="large" 
          :icon="VideoPlay" 
          :loading="isStarting" 
          :disabled="isButtonDisabled"
          @click="startAutoAnnotation"
        >
          {{ buttonText }}
        </el-button>
        <p class="action-hint">{{ actionHint }}</p>
      </div>
    </el-card>

    <!-- Console Section -->
    <el-card v-if="consoleLogs.length > 0" class="panel console-panel">
      <template #header>
        <div class="progress-header">
          <span class="card-title">执行控制台</span>
          <div style="display: flex; align-items: center; gap: 8px;">
            <el-tag v-if="isCompleted" type="success" size="small">已完成</el-tag>
            <el-tag v-else-if="isFailed" type="danger" size="small">失败</el-tag>
            <el-tag v-else-if="isProcessing" type="primary" size="small">
              <el-icon class="is-loading" style="margin-right: 4px;"><Loading /></el-icon>
              进行中
            </el-tag>
          </div>
        </div>
      </template>

      <div class="console-body" ref="consoleRef">
        <div v-for="(log, index) in consoleLogs" :key="index" class="console-line" :class="'console-' + log.level">
          <span class="console-time">{{ log.time }}</span>
          <span class="console-icon">{{ log.icon }}</span>
          <span class="console-msg">{{ log.message }}</span>
        </div>
        <div v-if="isProcessing" class="console-line console-info console-cursor">
          <span class="console-time">{{ currentTime }}</span>
          <span class="console-icon">⏳</span>
          <span class="console-msg">{{ currentStepText }}</span>
          <span class="cursor-blink">▊</span>
        </div>
      </div>

      <div v-if="isProcessing" style="margin-top: 12px;">
        <div class="progress-info">
          <span class="progress-label">{{ currentStepText }}</span>
          <span class="progress-percent">{{ progressPercent }}%</span>
        </div>
        <el-progress 
          :percentage="progressPercent" 
          :stroke-width="10"
        />
      </div>

      <div v-if="isCompleted" class="completion-notice">
        <el-alert 
          title="自动标注已完成！" 
          type="success" 
          :closable="false"
          description="请前往 Label Studio 查看标注结果。如需重新标注，请先上传新图片。"
        />
      </div>

      <div v-if="isFailed" class="error-notice">
        <el-alert 
          title="标注过程出现错误" 
          type="error" 
          :closable="false"
          description="请查看后端日志获取详细错误信息。"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VideoPlay, Loading } from '@element-plus/icons-vue'
import { autoAnnotationAPI } from '@/api'

const props = defineProps({ project: { type: Object, required: true } })
const emit = defineEmits(['refresh'])

const isStarting = ref(false)
const consoleRef = ref(null)
const consoleLogs = ref([])
const hasNewImagesSinceCompletion = ref(true)
let lastKnownTotalImages = 0
let pollTimer = null

const currentTime = computed(() => {
  const now = new Date()
  return now.toLocaleTimeString('zh-CN', { hour12: false })
})

const isProcessing = computed(() => {
  return ['DETECTING', 'CLEANING', 'SYNCING', 'UPLOADING'].includes(props.project.status)
})

const isCompleted = computed(() => props.project.status === 'COMPLETED')
const isFailed = computed(() => props.project.status === 'FAILED')

const isButtonDisabled = computed(() => {
  if (isProcessing.value || isStarting.value) return true
  if (isCompleted.value && !hasNewImagesSinceCompletion.value) return true
  return false
})

const buttonText = computed(() => {
  switch (props.project.status) {
    case 'DETECTING': return 'DINO检测中...'
    case 'CLEANING': return 'VLM清洗中...'
    case 'SYNCING': return '同步到Label Studio中...'
    case 'COMPLETED':
      return hasNewImagesSinceCompletion.value ? '重新执行自动标注' : '已完成（上传新图片后可再次执行）'
    case 'FAILED': return '重新执行自动标注'
    default: return '一键自动标注'
  }
})

const actionHint = computed(() => {
  if (isProcessing.value) return '标注正在后台执行，请耐心等待...'
  if (isCompleted.value && !hasNewImagesSinceCompletion.value) return '所有图片已处理完成，上传新图片后可再次执行'
  if (isCompleted.value) return '上次标注已完成，可重新执行'
  return '默认处理全部图片，包含 DINO 检测 + VLM 智能清洗'
})

const progressPercent = computed(() => {
  const map = { 'UPLOADING': 10, 'DETECTING': 30, 'CLEANING': 60, 'SYNCING': 85, 'COMPLETED': 100, 'FAILED': 0 }
  return map[props.project.status] || 0
})

const currentStepText = computed(() => {
  const map = {
    'UPLOADING': '上传图片中...',
    'DETECTING': 'DINO 目标检测中...',
    'CLEANING': 'VLM 智能清洗中...',
    'SYNCING': '同步标注到 Label Studio...',
    'COMPLETED': '自动标注已完成',
    'FAILED': '标注过程出错'
  }
  return map[props.project.status] || ''
})

const addLog = (message, level = 'info', icon = 'ℹ️') => {
  const now = new Date()
  const time = now.toLocaleTimeString('zh-CN', { hour12: false })
  consoleLogs.value.push({ time, message, level, icon })
  nextTick(() => {
    if (consoleRef.value) {
      consoleRef.value.scrollTop = consoleRef.value.scrollHeight
    }
  })
}

// 定义状态流转顺序，用于补全跳过的中间状态
const STATUS_FLOW = ['DETECTING', 'CLEANING', 'SYNCING', 'COMPLETED']
const STATUS_LOGS = {
  'DETECTING': [{ msg: '开始 Grounding DINO 目标检测...', level: 'info', icon: '🔍' }],
  'CLEANING': [
    { msg: 'DINO 检测完成', level: 'success', icon: '✅' },
    { msg: '开始 VLM 智能清洗与验证...', level: 'info', icon: '🧹' }
  ],
  'SYNCING': [
    { msg: 'VLM 清洗完成', level: 'success', icon: '✅' },
    { msg: '正在同步标注结果到 Label Studio...', level: 'info', icon: '📤' }
  ],
  'COMPLETED': [
    { msg: '标注结果已同步到 Label Studio', level: 'success', icon: '✅' }
  ]
}

let lastLoggedStatus = null

const logStatusTransition = (fromStatus, toStatus) => {
  const fromIdx = STATUS_FLOW.indexOf(fromStatus)
  const toIdx = STATUS_FLOW.indexOf(toStatus)
  
  if (toIdx < 0) return
  
  // Fill in all intermediate + target status logs
  const startIdx = Math.max(0, fromIdx + 1)
  for (let i = startIdx; i <= toIdx; i++) {
    const logs = STATUS_LOGS[STATUS_FLOW[i]]
    if (logs) {
      logs.forEach(l => addLog(l.msg, l.level, l.icon))
    }
  }
  
  if (toStatus === 'COMPLETED') {
    addLog(`自动标注全部完成！共处理 ${props.project.totalImages || 0} 张图片`, 'success', '🎉')
    lastKnownTotalImages = props.project.totalImages || 0
    hasNewImagesSinceCompletion.value = false
    stopPolling()
  }
}

watch(() => props.project.status, (newStatus, oldStatus) => {
  if (newStatus === oldStatus) return
  
  if (newStatus === 'FAILED') {
    addLog('标注过程出现错误，请查看后端日志', 'error', '❌')
    stopPolling()
    return
  }
  
  logStatusTransition(lastLoggedStatus, newStatus)
  lastLoggedStatus = newStatus
})

watch(() => props.project.totalImages, (newTotal) => {
  if (isCompleted.value && newTotal > lastKnownTotalImages) {
    hasNewImagesSinceCompletion.value = true
  }
})

const startPolling = () => {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    emit('refresh')
  }, 2000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const startAutoAnnotation = async () => {
  try {
    isStarting.value = true
    await ElMessageBox.confirm(
      `即将启动一键自动标注流程：\n- 项目：${props.project.name}\n- 类别数：${props.project.labels?.length || 0}\n- 范围：全部图片\n- VLM 智能清洗：开启\n\n此操作将在后台执行，请勿关闭页面。`,
      '确认启动', 
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning', distinguishCancelAndClose: true }
    )
    
    consoleLogs.value = []
    lastLoggedStatus = null
    addLog('正在启动自动标注流程...', 'info', '🚀')
    addLog(`项目: ${props.project.name} | 类别: ${props.project.labels?.join(', ')} | VLM清洗: 开启`, 'info', '📋')
    
    await autoAnnotationAPI.startAutoAnnotation(props.project.id, { processRange: 'all' })
    
    addLog('后端任务已创建，等待执行...', 'success', '✅')
    ElMessage.success({ message: '自动标注已启动！', duration: 3000 })
    
    // Start self-polling to keep refreshing project status
    startPolling()
    emit('refresh')
    
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      addLog('启动失败: ' + (error.message || '未知错误'), 'error', '❌')
      ElMessage.error('启动自动标注失败：' + (error.message || '未知错误'))
    }
  } finally {
    isStarting.value = false
  }
}

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.auto-annotation { }
.section-desc { color: var(--gray-500); font-size: 13px; margin-bottom: 20px; }
.panel { margin-bottom: 16px; }
.card-title { font-size: 14px; font-weight: 500; color: var(--gray-900); }
.info-row { display: flex; flex-wrap: wrap; gap: 24px; }
.info-item { display: flex; flex-direction: column; min-width: 120px; }
.info-label { font-size: 12px; color: var(--gray-400); margin-bottom: 4px; }
.info-value { font-size: 16px; font-weight: 600; color: var(--gray-900); }
.labels-inline { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
.action-center { text-align: center; padding: 12px 0; }
.action-hint { margin: 12px 0 0; font-size: 12px; color: var(--gray-400); }

.progress-header { display: flex; justify-content: space-between; align-items: center; }
.progress-info { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.progress-label { font-size: 13px; color: var(--gray-700); font-weight: 500; }
.progress-percent { font-size: 16px; color: var(--brand-600); font-weight: 600; }
.completion-notice { margin-top: 16px; }
.error-notice { margin-top: 16px; }

.console-body {
  background: #1e1e2e;
  border-radius: var(--radius-md);
  padding: 16px;
  max-height: 300px;
  overflow-y: auto;
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.7;
}

.console-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.console-time {
  color: #6c7086;
  flex-shrink: 0;
  font-size: 12px;
}

.console-icon {
  flex-shrink: 0;
}

.console-msg {
  color: #cdd6f4;
  word-break: break-all;
}

.console-success .console-msg { color: #a6e3a1; }
.console-error .console-msg { color: #f38ba8; }
.console-warn .console-msg { color: #f9e2af; }

.cursor-blink {
  color: #89b4fa;
  animation: blink 1s step-end infinite;
  margin-left: 2px;
}

@keyframes blink {
  50% { opacity: 0; }
}

.console-body::-webkit-scrollbar { width: 4px; }
.console-body::-webkit-scrollbar-track { background: transparent; }
.console-body::-webkit-scrollbar-thumb { background: #45475a; border-radius: 2px; }
</style>
