<template>
  <div class="training">
    <p class="section-desc">使用 Label Studio 中已审核的标注数据训练目标检测模型</p>

    <!-- 训练配置 -->
    <el-card v-if="!trainingStatus || trainingStatus.status === 'IDLE'" class="panel">
      <template #header>
        <span class="card-title">训练配置</span>
      </template>

      <el-form :model="trainingConfig" label-width="120px" @submit.prevent>
        <el-form-item label="模型名称">
          <el-input 
            v-model="trainingConfig.modelName" 
            placeholder="请输入模型名称"
            style="width: 400px;" />
        </el-form-item>

        <el-form-item label="训练轮数">
          <el-input-number 
            v-model="trainingConfig.epochs" 
            :min="10" 
            :max="500" 
            :step="10" />
          <span class="form-hint">最少 10 轮，建议 50-200 轮</span>
        </el-form-item>

        <el-form-item label="批次大小">
          <el-input-number 
            v-model="trainingConfig.batchSize" 
            :min="1" 
            :max="64" 
            :step="2" />
          <span class="form-hint">根据 GPU 内存调整</span>
        </el-form-item>

        <el-form-item label="图像尺寸">
          <el-input-number 
            v-model="trainingConfig.imageSize" 
            :min="320" 
            :max="1280" 
            :step="32" />
          <span class="form-hint">必须是 32 的倍数</span>
        </el-form-item>

        <el-form-item label="数据集划分">
          <el-row :gutter="20">
            <el-col :span="8">
              <el-input-number 
                v-model="trainingConfig.trainRatio" 
                :min="0" 
                :max="100" 
                :step="5" 
                style="width: 100%;" />
              <span class="ratio-label">训练集 %</span>
            </el-col>
            <el-col :span="8">
              <el-input-number 
                v-model="trainingConfig.valRatio" 
                :min="0" 
                :max="100" 
                :step="5" 
                style="width: 100%;" />
              <span class="ratio-label">验证集 %</span>
            </el-col>
            <el-col :span="8">
              <el-input-number 
                v-model="trainingConfig.testRatio" 
                :min="0" 
                :max="100" 
                :step="5" 
                style="width: 100%;" />
              <span class="ratio-label">测试集 %</span>
            </el-col>
          </el-row>
        </el-form-item>

        <el-form-item>
          <el-button 
            type="primary" 
            size="large" 
            @click="startTraining"
            :loading="isStarting">
            <el-icon><VideoPlay /></el-icon>
            开始训练
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 训练进度 + 控制台 -->
    <el-card v-if="trainingStatus && trainingStatus.status !== 'IDLE'" class="panel">
      <template #header>
        <div class="progress-header">
          <span class="card-title">训练进度</span>
          <div style="display: flex; align-items: center; gap: 8px;">
            <span v-if="elapsedDisplay" class="elapsed-timer">⏱ {{ elapsedDisplay }}</span>
            <el-tag 
              :type="getStatusType(trainingStatus.status)" 
              size="small">
              {{ getStatusText(trainingStatus.status) }}
            </el-tag>
          </div>
        </div>
      </template>

      <div class="training-progress">
        <div v-if="trainingStatus.status === 'TRAINING'" class="progress-stats">
          <div class="progress-item">
            <span class="label">当前轮次</span>
            <span class="value">{{ trainingStatus.currentEpoch }} / {{ trainingStatus.totalEpochs }}</span>
          </div>
          <div class="progress-item">
            <span class="label">训练损失</span>
            <span class="value">{{ trainingStatus.trainLoss?.toFixed(4) || '-' }}</span>
          </div>
          <div class="progress-item">
            <span class="label">验证损失</span>
            <span class="value">{{ trainingStatus.valLoss?.toFixed(4) || '-' }}</span>
          </div>
          <div class="progress-item">
            <span class="label">已用时间</span>
            <span class="value">{{ elapsedDisplay || '-' }}</span>
          </div>
        </div>

        <el-progress 
          v-if="trainingStatus.status === 'TRAINING'"
          :percentage="trainingProgress" 
          :stroke-width="16"
          :format="formatProgress"
          style="margin-bottom: 16px;" />

        <!-- Training Console -->
        <div class="console-body" ref="consoleRef">
          <div v-for="(log, index) in consoleLogs" :key="index" class="console-line" :class="'console-' + log.level">
            <span class="console-time">{{ log.time }}</span>
            <span class="console-icon">{{ log.icon }}</span>
            <span class="console-msg">{{ log.message }}</span>
          </div>
          <div v-if="['PREPARING', 'TRAINING'].includes(trainingStatus.status)" class="console-line console-info">
            <span class="console-time">{{ currentTimeStr }}</span>
            <span class="console-icon">⏳</span>
            <span class="console-msg">{{ getStatusText(trainingStatus.status) }}...</span>
            <span class="cursor-blink">▊</span>
          </div>
        </div>

        <div v-if="trainingStatus.status === 'COMPLETED'" class="metrics-display">
          <el-alert 
            title="训练完成！" 
            type="success" 
            :closable="false"
            :description="`总耗时 ${elapsedDisplay || formatDuration(trainingStatus.trainingDuration)}`"
            style="margin-top: 16px; margin-bottom: 20px;" />

          <el-descriptions title="训练指标" :column="2" border>
            <el-descriptions-item label="最终训练损失">
              {{ trainingStatus.finalTrainLoss?.toFixed(4) || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="最终验证损失">
              {{ trainingStatus.finalValLoss?.toFixed(4) || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="mAP@0.5">
              {{ trainingStatus.map50?.toFixed(4) || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="mAP@0.5:0.95">
              {{ trainingStatus.map5095?.toFixed(4) || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="精确率 (Precision)">
              {{ trainingStatus.precision?.toFixed(4) || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="召回率 (Recall)">
              {{ trainingStatus.recall?.toFixed(4) || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="训练时长">
              {{ elapsedDisplay || formatDuration(trainingStatus.trainingDuration) }}
            </el-descriptions-item>
            <el-descriptions-item label="模型路径">
              {{ trainingStatus.modelPath || '-' }}
            </el-descriptions-item>
          </el-descriptions>

          <div style="margin-top: 20px; text-align: center;">
            <el-button type="primary" @click="goToTest">
              <el-icon><Picture /></el-icon>
              测试模型
            </el-button>
            <el-button @click="resetTraining">
              <el-icon><RefreshLeft /></el-icon>
              重新训练
            </el-button>
          </div>
        </div>

        <div v-if="trainingStatus.status === 'FAILED'" class="error-display">
          <el-alert 
            title="训练失败" 
            type="error" 
            :closable="false"
            :description="trainingStatus.errorMessage || '未知错误'"
            style="margin-top: 16px;" />
          
          <div style="margin-top: 20px; text-align: center;">
            <el-button type="primary" @click="resetTraining">
              <el-icon><RefreshLeft /></el-icon>
              重新训练
            </el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 模型测试 -->
    <el-card v-if="showTestSection" class="panel">
      <template #header>
        <span class="card-title">模型测试</span>
      </template>

      <div class="test-section">
        <el-alert
          v-if="selectedTestModel"
          type="info"
          :closable="false"
          style="margin-bottom: 16px;">
          <template #title>
            当前测试模型：{{ selectedTestModel.runName || selectedTestModel.id }}
            <span v-if="selectedTestModel.map50 != null"> · mAP@0.5 {{ selectedTestModel.map50.toFixed(4) }}</span>
          </template>
        </el-alert>

        <el-upload
          class="test-uploader"
          drag
          :auto-upload="false"
          :on-change="handleImageSelect"
          :show-file-list="false"
          accept="image/*">
          <div class="test-dropzone" :class="{ 'has-image': testImage }">
            <div v-if="!testImage" class="empty-upload">
              <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
              <div class="el-upload__text">
                拖拽图片到此处或 <em>点击上传</em>
              </div>
              <div class="el-upload__tip">
                支持 jpg/png 格式图片
              </div>
            </div>

            <div v-else class="preview-upload">
              <img :src="testImage" alt="测试图片" />
              <div class="preview-actions">
                <el-button size="small" @click.stop="clearTestImage">更换图片</el-button>
                <el-button 
                  type="primary" 
                  size="small"
                  @click.stop="runDetection"
                  :loading="isDetecting">
                  <el-icon><Search /></el-icon>
                  开始检测
                </el-button>
              </div>
            </div>
          </div>
        </el-upload>

        <div v-if="testImage" class="test-result">
          <div v-if="detectionResults.length > 0" class="detection-results">
            <h4>检测结果</h4>
            <el-table :data="detectionResults" style="width: 100%">
              <el-table-column prop="label" label="类别" width="150" />
              <el-table-column prop="confidence" label="置信度" width="120">
                <template #default="{ row }">
                  {{ (row.confidence * 100).toFixed(2) }}%
                </template>
              </el-table-column>
              <el-table-column prop="bbox" label="边界框">
                <template #default="{ row }">
                  [{{ row.bbox.join(', ') }}]
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </div>
    </el-card>

    <el-card class="panel">
      <template #header>
        <div class="history-header">
          <span class="card-title">训练历史</span>
          <el-button text size="small" @click="loadTrainingHistory">刷新</el-button>
        </div>
      </template>

      <el-table :data="trainingHistory" v-loading="historyLoading" stripe empty-text="暂无训练记录">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="runName" label="运行名称" min-width="180" />
        <el-table-column prop="modelType" label="模型" width="120" />
        <el-table-column prop="epochs" label="轮数" width="80" align="center" />
        <el-table-column prop="batchSize" label="批次" width="80" align="center" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getHistoryStatusType(row.status)" size="small">
              {{ getHistoryStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="mAP@0.5" width="110">
          <template #default="{ row }">
            {{ row.map50 != null ? row.map50.toFixed(4) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="Precision" width="110">
          <template #default="{ row }">
            {{ row.precision != null ? row.precision.toFixed(4) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="Recall" width="100">
          <template #default="{ row }">
            {{ row.recall != null ? row.recall.toFixed(4) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="开始时间" min-width="170">
          <template #default="{ row }">
            {{ formatDateTime(row.startedAt || row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="测试模型" width="130" align="center">
          <template #default="{ row }">
            <el-tag v-if="selectedTestModel?.id === row.id" type="success" size="small">当前</el-tag>
            <el-button
              v-else-if="row.status === 'COMPLETED' && row.bestModelPath"
              type="primary"
              link
              size="small"
              @click="selectTestModel(row)">
              用于测试
            </el-button>
            <span v-else style="color: var(--gray-400);">-</span>
          </template>
        </el-table-column>
        <el-table-column label="模型路径" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.bestModelPath || '-' }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { projectAPI, trainingAPI } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VideoPlay, Picture, RefreshLeft, UploadFilled, Search } from '@element-plus/icons-vue'

const props = defineProps({
  project: { type: Object, required: true }
})

const trainingConfig = ref({
  modelName: '',
  epochs: 100,
  batchSize: 16,
  imageSize: 640,
  trainRatio: 70,
  valRatio: 20,
  testRatio: 10
})

const isStarting = ref(false)
const trainingStatus = ref(null)
const showTestSection = ref(false)
const testImage = ref(null)
const testFile = ref(null)
const isDetecting = ref(false)
const detectionResults = ref([])
const consoleLogs = ref([])
const consoleRef = ref(null)
const trainingHistory = ref([])
const historyLoading = ref(false)
const selectedTestModel = ref(null)

let pollInterval = null
let trainingStartTime = null
let elapsedTimerInterval = null
let lastLoggedEpoch = 0
const elapsedSeconds = ref(0)

const currentTimeStr = computed(() => {
  const now = new Date()
  return now.toLocaleTimeString('zh-CN', { hour12: false })
})

const elapsedDisplay = computed(() => {
  if (elapsedSeconds.value <= 0) return ''
  const h = Math.floor(elapsedSeconds.value / 3600)
  const m = Math.floor((elapsedSeconds.value % 3600) / 60)
  const s = elapsedSeconds.value % 60
  if (h > 0) return `${h}h ${m}m ${s}s`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
})

const trainingProgress = computed(() => {
  if (!trainingStatus.value || !trainingStatus.value.currentEpoch) return 0
  return Math.round((trainingStatus.value.currentEpoch / trainingStatus.value.totalEpochs) * 100)
})

const formatProgress = (percentage) => {
  return `${percentage}%`
}

const getStatusType = (status) => {
  const typeMap = {
    'PREPARING': 'info',
    'TRAINING': 'primary',
    'COMPLETED': 'success',
    'FAILED': 'danger'
  }
  return typeMap[status] || 'info'
}

const getStatusText = (status) => {
  const textMap = {
    'PREPARING': '准备中',
    'TRAINING': '训练中',
    'COMPLETED': '已完成',
    'FAILED': '失败'
  }
  return textMap[status] || status
}

const formatDuration = (seconds) => {
  if (!seconds) return '-'
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const secs = Math.floor(seconds % 60)
  return `${hours}h ${minutes}m ${secs}s`
}

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

const startElapsedTimer = () => {
  trainingStartTime = Date.now()
  elapsedSeconds.value = 0
  if (elapsedTimerInterval) clearInterval(elapsedTimerInterval)
  elapsedTimerInterval = setInterval(() => {
    elapsedSeconds.value = Math.floor((Date.now() - trainingStartTime) / 1000)
  }, 1000)
}

const stopElapsedTimer = () => {
  if (elapsedTimerInterval) {
    clearInterval(elapsedTimerInterval)
    elapsedTimerInterval = null
  }
}

const startTraining = async () => {
  if (!trainingConfig.value.modelName) {
    ElMessage.warning('请输入模型名称')
    return
  }

  const total = trainingConfig.value.trainRatio + trainingConfig.value.valRatio + trainingConfig.value.testRatio
  if (total !== 100) {
    ElMessage.warning('数据集划分比例之和必须为 100%')
    return
  }

  try {
    isStarting.value = true
    await ElMessageBox.confirm(
      `确认开始训练模型？\n模型名称：${trainingConfig.value.modelName}\n训练轮数：${trainingConfig.value.epochs}`,
      '确认训练',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )

    consoleLogs.value = []
    lastLoggedEpoch = 0
    addLog('正在提交训练任务...', 'info', '🚀')

    const response = await projectAPI.startTraining(props.project.id, trainingConfig.value)
    
    if (response.data?.status === 'FAILED') {
      trainingStatus.value = response.data
      addLog('启动训练失败: ' + (response.data.errorMessage || response.data.message || '未知错误'), 'error', '❌')
      ElMessage.error('启动训练失败：' + (response.data.errorMessage || response.data.message))
      return
    }
    
    trainingStatus.value = response.data
    
    addLog(`模型: ${trainingConfig.value.modelName} | 轮数: ${trainingConfig.value.epochs} | 批次: ${trainingConfig.value.batchSize}`, 'info', '📋')
    addLog('训练任务已创建，开始准备数据...', 'success', '✅')
    
    ElMessage.success('训练已启动')
    loadTrainingHistory()
    startPolling()
    startElapsedTimer()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      addLog('启动训练失败: ' + (error.message || '未知错误'), 'error', '❌')
      ElMessage.error('启动训练失败：' + (error.message || '未知错误'))
    }
  } finally {
    isStarting.value = false
  }
}

const loadTrainingStatus = async () => {
  try {
    const response = await projectAPI.getTrainingStatus(props.project.id)
    const newStatus = response.data
    const oldStatusVal = trainingStatus.value?.status
    trainingStatus.value = newStatus
    
    if (newStatus.status === 'IDLE') {
      stopPolling()
      stopElapsedTimer()
      return
    }

    if (newStatus.status !== oldStatusVal) {
      switch (newStatus.status) {
        case 'TRAINING':
          addLog('数据准备完成，开始训练...', 'success', '✅')
          break
        case 'COMPLETED':
          addLog('训练完成！', 'success', '🎉')
          if (newStatus.map50) addLog(`mAP@0.5: ${newStatus.map50.toFixed(4)}`, 'success', '📊')
          showTestSection.value = true
          loadTrainingHistory(true)
          stopPolling()
          stopElapsedTimer()
          break
        case 'FAILED':
          addLog('训练失败: ' + (newStatus.errorMessage || '未知错误'), 'error', '❌')
          loadTrainingHistory()
          stopPolling()
          stopElapsedTimer()
          break
      }
    }
    
    if (
      newStatus.status === 'TRAINING' &&
      newStatus.currentEpoch &&
      newStatus.currentEpoch % 10 === 0 &&
      newStatus.currentEpoch > lastLoggedEpoch
    ) {
      lastLoggedEpoch = newStatus.currentEpoch
      addLog(`Epoch ${newStatus.currentEpoch}/${newStatus.totalEpochs} - loss: ${newStatus.trainLoss?.toFixed(4) || '?'}`, 'info', '📈')
    }
  } catch (error) {
    console.error('获取训练状态失败:', error)
  }
}

const startPolling = () => {
  if (pollInterval) return
  pollInterval = setInterval(() => {
    loadTrainingStatus()
  }, 3000)
}

const stopPolling = () => {
  if (pollInterval) {
    clearInterval(pollInterval)
    pollInterval = null
  }
}

const resetTraining = () => {
  trainingStatus.value = null
  showTestSection.value = false
  testImage.value = null
  detectionResults.value = []
  consoleLogs.value = []
  elapsedSeconds.value = 0
  lastLoggedEpoch = 0
  stopElapsedTimer()
}

const goToTest = () => {
  showTestSection.value = true
  setTimeout(() => {
    document.querySelector('.test-section')?.scrollIntoView({ behavior: 'smooth' })
  }, 100)
}

const handleImageSelect = (file) => {
  testFile.value = file.raw
  const reader = new FileReader()
  reader.onload = (e) => {
    testImage.value = e.target.result
    detectionResults.value = []
  }
  reader.readAsDataURL(file.raw)
}

const clearTestImage = () => {
  testImage.value = null
  testFile.value = null
  detectionResults.value = []
}

const runDetection = async () => {
  if (!testFile.value) return
  if (!selectedTestModel.value?.bestModelPath) {
    ElMessage.warning('请先在训练历史中选择一个已完成模型')
    return
  }

  try {
    isDetecting.value = true
    const formData = new FormData()
    formData.append('image', testFile.value)
    if (selectedTestModel.value?.id) {
      formData.append('modelId', selectedTestModel.value.id)
    }
    if (selectedTestModel.value?.bestModelPath) {
      formData.append('modelPath', selectedTestModel.value.bestModelPath)
    }

    const response = await projectAPI.detectWithTrainedModel(props.project.id, formData)
    detectionResults.value = response.data.detections || []
    
    if (detectionResults.value.length === 0) {
      ElMessage.info('未检测到目标')
    } else {
      ElMessage.success(`检测到 ${detectionResults.value.length} 个目标`)
    }
  } catch (error) {
    ElMessage.error('检测失败：' + (error.message || '未知错误'))
  } finally {
    isDetecting.value = false
  }
}

const loadTrainingHistory = async (preferLatestCompleted = false) => {
  try {
    historyLoading.value = true
    const response = await trainingAPI.getTrainingRecordsByProject(props.project.id)
    trainingHistory.value = response.data || []
    syncSelectedTestModel(preferLatestCompleted)
  } catch (error) {
    console.error('加载训练历史失败:', error)
  } finally {
    historyLoading.value = false
  }
}

const syncSelectedTestModel = (preferLatestCompleted = false) => {
  const completed = trainingHistory.value.filter(record => record.status === 'COMPLETED' && record.bestModelPath)
  if (completed.length === 0) {
    selectedTestModel.value = null
    return
  }

  if (preferLatestCompleted || !selectedTestModel.value) {
    selectedTestModel.value = completed[0]
    return
  }

  const stillExists = completed.find(record => record.id === selectedTestModel.value.id)
  selectedTestModel.value = stillExists || completed[0]
}

const selectTestModel = (record) => {
  selectedTestModel.value = record
  showTestSection.value = true
  detectionResults.value = []
  ElMessage.success(`已切换测试模型：${record.runName || record.id}`)
}

const getHistoryStatusType = (status) => {
  return {
    'PENDING': 'info',
    'RUNNING': 'warning',
    'COMPLETED': 'success',
    'FAILED': 'danger',
    'CANCELLED': 'info'
  }[status] || 'info'
}

const getHistoryStatusText = (status) => {
  return {
    'PENDING': '等待中',
    'RUNNING': '训练中',
    'COMPLETED': '已完成',
    'FAILED': '失败',
    'CANCELLED': '已取消'
  }[status] || status
}

const formatDateTime = (value) => {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  loadTrainingStatus()
  loadTrainingHistory()
})

onUnmounted(() => {
  stopPolling()
  stopElapsedTimer()
})
</script>

<style scoped>
.training { }
.section-desc { color: var(--gray-500); font-size: 13px; margin-bottom: 20px; }
.panel { margin-bottom: 16px; }
.card-title { font-size: 14px; font-weight: 500; color: var(--gray-900); }
.form-hint { margin-left: 12px; font-size: 12px; color: var(--gray-400); }
.ratio-label { display: block; font-size: 12px; color: var(--gray-400); margin-top: 4px; }

.progress-header { display: flex; justify-content: space-between; align-items: center; }
.elapsed-timer { font-size: 13px; font-weight: 600; color: var(--brand-600); font-family: 'Cascadia Code', 'Consolas', monospace; }

.training-progress { }
.progress-stats { 
  display: flex; 
  gap: 32px; 
  margin-bottom: 20px;
  padding: 16px;
  background: var(--gray-50);
  border-radius: 8px;
}
.progress-item { display: flex; flex-direction: column; }
.progress-item .label { font-size: 12px; color: var(--gray-500); margin-bottom: 4px; }
.progress-item .value { font-size: 18px; font-weight: 600; color: var(--gray-900); }

.metrics-display { }
.error-display { }

.console-body {
  background: #1e1e2e;
  border-radius: var(--radius-md);
  padding: 16px;
  max-height: 250px;
  overflow-y: auto;
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.7;
  margin-bottom: 4px;
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

.console-icon { flex-shrink: 0; }
.console-msg { color: #cdd6f4; word-break: break-all; }
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

.test-section { }
.test-uploader { margin-bottom: 20px; }
.test-uploader :deep(.el-upload) { width: 100%; }
.test-uploader :deep(.el-upload-dragger) {
  width: 100%;
  padding: 0;
  border-radius: 8px;
  overflow: hidden;
}
.test-dropzone {
  min-height: 360px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.test-dropzone.has-image {
  min-height: auto;
  background: var(--gray-50);
}
.empty-upload {
  min-height: 360px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.preview-upload {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 24px;
}
.preview-upload img {
  max-width: min(640px, 100%);
  max-height: 560px;
  border-radius: 8px;
  object-fit: contain;
  box-shadow: 0 1px 8px rgba(0, 0, 0, 0.08);
}
.preview-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
}
.test-result { margin-top: 20px; }
.detection-results { margin-top: 20px; }
.detection-results h4 { 
  font-size: 14px; 
  font-weight: 500; 
  color: var(--gray-900); 
  margin-bottom: 12px;
}
.history-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
