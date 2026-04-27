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
      <div class="mode-settings">
        <div class="setting-row">
          <span class="setting-label">自动标注模式</span>
          <el-radio-group v-model="mode" :disabled="isProcessing || isStarting">
            <el-radio-button label="DINO_VLM">DINO + VLM 清洗</el-radio-button>
            <el-radio-button label="DINO_THRESHOLD">DINO 阈值过滤</el-radio-button>
          </el-radio-group>
        </div>

        <div v-if="mode === 'DINO_THRESHOLD'" class="setting-row">
          <span class="setting-label">保留阈值</span>
          <el-input-number
            v-model="scoreThreshold"
            :min="0"
            :max="1"
            :step="0.05"
            :precision="2"
            :disabled="isProcessing || isStarting"
          />
        </div>

        <div v-if="mode === 'DINO_VLM'" class="vlm-config">
          <el-form label-width="96px" size="small">
            <el-form-item label="Base URL">
              <el-input v-model="vlmConfig.vlmBaseUrl" :disabled="isProcessing || isStarting" />
            </el-form-item>
            <el-form-item label="Model">
              <el-input v-model="vlmConfig.vlmModelName" :disabled="isProcessing || isStarting" />
            </el-form-item>
            <el-form-item label="API Key">
              <el-input v-model="vlmConfig.vlmApiKey" type="password" show-password :disabled="isProcessing || isStarting" />
            </el-form-item>
            <el-form-item>
              <el-button size="small" :loading="savingVlm" @click="saveVlmConfig">保存配置</el-button>
              <el-button size="small" :loading="testingVlm" @click="testVlmConnectivity">测试连通</el-button>
              <el-tag v-if="vlmTestResult === 'ok'" type="success" size="small">连通正常</el-tag>
              <el-tag v-else-if="vlmTestResult === 'fail'" type="danger" size="small">连通失败</el-tag>
            </el-form-item>
          </el-form>
        </div>
      </div>
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
        <el-button v-if="activeJobId && isProcessing" size="large" @click="cancelCurrentJob">取消任务</el-button>
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
            <el-tag v-else-if="isCancelled" type="warning" size="small">已取消</el-tag>
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
        <div class="stage-indicator">
          <el-tag size="small" type="info">当前阶段: {{ stageLabel }}</el-tag>
          <span class="stage-progress-hint">流程总进度 {{ progressPercent }}%</span>
        </div>

        <div class="progress-info" style="margin-top: 12px;">
          <span class="progress-label">图像处理进度</span>
          <span class="progress-percent">
            {{ jobStatus?.processedImages || 0 }} / {{ jobStatus?.totalImages || 0 }}
            ({{ imageProgressPercent }}%)
          </span>
        </div>
        <el-progress
          :percentage="imageProgressPercent"
          :stroke-width="10"
          :status="jobStatus?.currentStage === 'DINO' || jobStatus?.currentStage === 'INIT' ? undefined : 'success'"
        />

        <div v-if="jobStatus" class="job-metrics" style="margin-top: 8px;">
          <span>保留 {{ jobStatus.keptDetections || 0 }}</span>
          <span>舍弃 {{ jobStatus.discardedDetections || 0 }}</span>
        </div>
      </div>

      <div v-if="isCompleted" class="completion-notice">
        <el-alert 
          title="自动标注已完成！" 
          type="success" 
          :closable="false"
          description="请前往 Label Studio 查看标注结果。如需重新标注，可直接重新执行。"
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
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VideoPlay, Loading } from '@element-plus/icons-vue'
import { autoAnnotationAPI, userAPI } from '@/api'

const props = defineProps({ project: { type: Object, required: true } })
const emit = defineEmits(['refresh'])

const isStarting = ref(false)
const consoleRef = ref(null)
const consoleLogs = ref([])
const hasNewImagesSinceCompletion = ref(true)
const mode = ref('DINO_VLM')
const scoreThreshold = ref(0.7)
const activeJobId = ref(null)
const jobStatus = ref(null)
const savingVlm = ref(false)
const testingVlm = ref(false)
const vlmTestResult = ref(null)
const vlmConfig = ref({
  vlmBaseUrl: '',
  vlmModelName: '',
  vlmApiKey: ''
})
let lastKnownTotalImages = 0
let pollTimer = null

const currentTime = computed(() => {
  const now = new Date()
  return now.toLocaleTimeString('zh-CN', { hour12: false })
})

const isProcessing = computed(() => {
  if (jobStatus.value && ['PENDING', 'RUNNING', 'CANCELLING'].includes(jobStatus.value.status)) return true
  return ['DETECTING', 'CLEANING', 'SYNCING', 'UPLOADING'].includes(props.project.status)
})

const isCompleted = computed(() => jobStatus.value?.status === 'COMPLETED' || props.project.status === 'COMPLETED')
const isFailed = computed(() => jobStatus.value?.status === 'FAILED' || props.project.status === 'FAILED')
const isCancelled = computed(() => jobStatus.value?.status === 'CANCELLED')

const isButtonDisabled = computed(() => {
  if (isProcessing.value || isStarting.value) return true
  return false
})

const buttonText = computed(() => {
  if (jobStatus.value && ['PENDING', 'RUNNING', 'CANCELLING'].includes(jobStatus.value.status)) {
    return currentStepText.value || '自动标注进行中...'
  }
  switch (props.project.status) {
    case 'DETECTING': return 'DINO检测中...'
    case 'CLEANING': return 'VLM清洗中...'
    case 'SYNCING': return '同步到Label Studio中...'
    case 'COMPLETED':
      return '重新执行自动标注'
    case 'FAILED': return '重新执行自动标注'
    default: return '一键自动标注'
  }
})

const actionHint = computed(() => {
  if (isProcessing.value) return '标注正在后台执行，可关闭页面后回来查看进度'
  if (isCompleted.value) return '上次标注已完成，可重新执行'
  return mode.value === 'DINO_VLM'
    ? '默认处理全部图片，包含 DINO 检测 + VLM 智能清洗'
    : `默认处理全部图片，仅保留 score >= ${scoreThreshold.value} 的检测框`
})

const progressPercent = computed(() => {
  if (jobStatus.value?.progressPercent != null) {
    return Math.round(jobStatus.value.progressPercent)
  }
  const map = { 'UPLOADING': 10, 'DETECTING': 30, 'CLEANING': 60, 'SYNCING': 85, 'COMPLETED': 100, 'FAILED': 0 }
  return map[props.project.status] || 0
})

const imageProgressPercent = computed(() => {
  if (jobStatus.value?.imageProgressPercent != null) {
    return Math.min(100, Math.round(jobStatus.value.imageProgressPercent))
  }
  if (!jobStatus.value) return 0
  const processed = jobStatus.value.processedImages || 0
  const total = jobStatus.value.totalImages || props.project.totalImages || 0
  if (total <= 0) return 0
  return Math.min(100, Math.round(processed * 100 / total))
})

const stageLabel = computed(() => {
  const stage = jobStatus.value?.currentStage
  const map = {
    INIT: '初始化',
    DINO: 'DINO 目标检测',
    VLM: 'VLM 智能清洗',
    THRESHOLD_FILTER: '阈值过滤',
    SYNC: '同步到 Label Studio'
  }
  return map[stage] || '准备中'
})

const currentStepText = computed(() => {
  if (jobStatus.value?.currentStage) {
    const processed = jobStatus.value.processedImages || 0
    const total = jobStatus.value.totalImages || props.project.totalImages || 0
    const stageMap = {
      INIT: '任务初始化中...',
      DINO: total > 0 ? `DINO 目标检测中... ${processed}/${total} 张` : 'DINO 目标检测中...',
      VLM: 'VLM 智能清洗中...',
      THRESHOLD_FILTER: 'DINO 置信度过滤中...',
      SYNC: '同步标注到 Label Studio...'
    }
    return stageMap[jobStatus.value.currentStage] || ''
  }
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
let lastLoggedProcessed = 0

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
  if (activeJobId.value) return
  
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
  pollTimer = setInterval(pollJobOrProject, 2000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const pollJobOrProject = async () => {
  try {
    if (activeJobId.value) {
      const res = await autoAnnotationAPI.getJob(activeJobId.value)
      jobStatus.value = res.data
      syncLogsFromJob()
      if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(jobStatus.value.status)) {
        stopPolling()
      }
    }
  } finally {
    emit('refresh')
  }
}

const syncLogsFromJob = () => {
  if (!jobStatus.value) return
  const stage = jobStatus.value.currentStage
  if (stage && stage !== lastLoggedStatus) {
    const stageLogs = {
      INIT: '任务已创建，正在初始化...',
      DINO: '开始 Grounding DINO 目标检测...',
      VLM: '开始 VLM 智能清洗与验证...',
      THRESHOLD_FILTER: `按 score >= ${scoreThreshold.value} 过滤 DINO 检测框...`,
      SYNC: '正在同步标注结果到 Label Studio...'
    }
    if (stageLogs[stage]) addLog(stageLogs[stage], 'info', '⚙️')
    lastLoggedStatus = stage
    if (stage === 'DINO') {
      lastLoggedProcessed = 0
    }
  }
  if (stage === 'DINO') {
    const processed = jobStatus.value.processedImages || 0
    const total = jobStatus.value.totalImages || props.project.totalImages || 0
    const step = Math.max(10, Math.ceil((total || 100) / 20))
    if (processed > 0 && (processed - lastLoggedProcessed >= step || processed === total)) {
      addLog(`DINO 已检测 ${processed}/${total} 张`, 'info', '🔎')
      lastLoggedProcessed = processed
    }
  }
  if (jobStatus.value.status === 'COMPLETED') {
    addLog(`自动标注完成，保留 ${jobStatus.value.keptDetections || 0} 个检测框`, 'success', '✅')
    lastKnownTotalImages = props.project.totalImages || 0
    hasNewImagesSinceCompletion.value = false
  } else if (jobStatus.value.status === 'FAILED') {
    addLog(`任务失败: ${jobStatus.value.errorMessage || '未知错误'}`, 'error', '❌')
  } else if (jobStatus.value.status === 'CANCELLED') {
    addLog('任务已取消', 'warn', '⚠️')
  }
}

const loadVlmConfig = async () => {
  try {
    const res = await userAPI.getModelConfig()
    const data = res.data || {}
    vlmConfig.value = {
      vlmBaseUrl: data.vlmBaseUrl || data.vlm_base_url || '',
      vlmModelName: data.vlmModelName || data.vlm_model_name || '',
      vlmApiKey: data.vlmApiKey || data.vlm_api_key || ''
    }
  } catch (error) {
    console.warn('Load VLM config failed', error)
  }
}

const saveVlmConfig = async () => {
  savingVlm.value = true
  try {
    await userAPI.updateModelConfig(vlmConfig.value)
    vlmTestResult.value = null
    ElMessage.success('VLM 配置已保存')
  } finally {
    savingVlm.value = false
  }
}

const testVlmConnectivity = async () => {
  testingVlm.value = true
  try {
    const res = await userAPI.testVlmModelConfig()
    vlmTestResult.value = res.data?.success ? 'ok' : 'fail'
    if (vlmTestResult.value === 'ok') ElMessage.success('VLM 连通正常')
    else ElMessage.error('VLM 连通失败，请检查配置')
  } catch {
    vlmTestResult.value = 'fail'
  } finally {
    testingVlm.value = false
  }
}

const restoreLatestJob = async () => {
  try {
    const res = await autoAnnotationAPI.getLatestJob(props.project.id)
    if (res.data && res.data.jobId) {
      jobStatus.value = res.data
      activeJobId.value = res.data.jobId
      if (['PENDING', 'RUNNING', 'CANCELLING'].includes(res.data.status)) {
        consoleLogs.value = []
        addLog('已恢复后台自动标注任务进度', 'info', '↩️')
        startPolling()
      }
    }
  } catch (error) {
    console.warn('Restore latest job failed', error)
  }
}

const startAutoAnnotation = async () => {
  try {
    isStarting.value = true
    await ElMessageBox.confirm(
      mode.value === 'DINO_VLM'
        ? `即将启动自动标注流程：\n- 模式：DINO + VLM 清洗\n- 项目：${props.project.name}\n- 类别数：${props.project.labels?.length || 0}\n- 范围：全部图片`
        : `即将启动自动标注流程：\n- 模式：DINO 阈值过滤\n- 阈值：score >= ${scoreThreshold.value}\n- 项目：${props.project.name}\n- 范围：全部图片`,
      '确认启动', 
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning', distinguishCancelAndClose: true }
    )
    
    consoleLogs.value = []
    lastLoggedStatus = null
    lastLoggedProcessed = 0
    jobStatus.value = null
    addLog('正在启动自动标注流程...', 'info', '🚀')
    addLog(`项目: ${props.project.name} | 类别: ${props.project.labels?.join(', ')} | 模式: ${mode.value === 'DINO_VLM' ? 'DINO + VLM' : 'DINO 阈值过滤'}`, 'info', '📋')
    
    const res = await autoAnnotationAPI.startAutoAnnotation(props.project.id, {
      processRange: 'all',
      mode: mode.value,
      scoreThreshold: scoreThreshold.value
    })
    activeJobId.value = res.data?.jobId || null
    
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

const cancelCurrentJob = async () => {
  if (!activeJobId.value) return
  await ElMessageBox.confirm('确认取消当前自动标注任务？', '取消任务', { type: 'warning' })
  await autoAnnotationAPI.cancelJob(activeJobId.value)
  ElMessage.success('已提交取消请求')
  await pollJobOrProject()
}

onMounted(() => {
  loadVlmConfig()
  restoreLatestJob()
})

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
.mode-settings { margin-bottom: 16px; }
.setting-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.setting-label { width: 96px; font-size: 13px; color: var(--gray-600); font-weight: 500; }
.vlm-config { border-top: 1px solid var(--gray-100); padding-top: 12px; }

.progress-header { display: flex; justify-content: space-between; align-items: center; }
.stage-indicator { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 8px 12px; background: var(--gray-50); border-radius: var(--radius-sm); }
.stage-progress-hint { font-size: 12px; color: var(--gray-500); }
.progress-info { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.progress-label { font-size: 13px; color: var(--gray-700); font-weight: 500; }
.progress-percent { font-size: 16px; color: var(--brand-600); font-weight: 600; }
.job-metrics { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; font-size: 12px; color: var(--gray-500); }
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
