<template>
  <div class="training">
    <p class="section-desc">使用 Label Studio 中已审核的标注数据训练目标检测模型</p>

    <!-- 训练配置 -->
    <el-card v-if="!trainingStatus || trainingStatus.status === 'IDLE'" class="panel">
      <template #header>
        <span class="card-title">训练配置</span>
      </template>

      <el-form :model="trainingConfig" label-width="120px" @submit.prevent>
        <el-form-item label="训练模式">
          <el-radio-group v-model="trainingConfig.mode">
            <el-radio-button label="manual">手动配置</el-radio-button>
            <el-radio-button label="automl">AutoML 自动配置</el-radio-button>
          </el-radio-group>
          <span class="form-hint">AutoML 会按数据规模自动选择模型、轮数、batch 和图像尺寸</span>
        </el-form-item>

        <el-form-item label="模型名称">
          <el-input 
            v-model="trainingConfig.modelName" 
            placeholder="请输入模型名称"
            style="width: 400px;" />
        </el-form-item>

        <el-alert
          v-if="trainingConfig.mode === 'automl'"
          type="info"
          :closable="false"
          show-icon
          class="automl-alert">
          <template #title>
            AutoML 模式会参考数据量和类别数自动配置：小数据优先 yolov8n，中等数据 yolov8s，大数据 yolov8m；默认使用预训练权重、GPU 0、80/20 train/val 划分。
          </template>
        </el-alert>

        <template v-if="trainingConfig.mode === 'manual'">
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

        <el-form-item label="训练设备">
          <el-select v-model="trainingConfig.device" style="width: 180px;">
            <el-option label="GPU 0" value="0" />
            <el-option label="CPU" value="cpu" />
          </el-select>
          <span class="form-hint">默认使用 GPU 0；无 CUDA 时算法服务自动回退 CPU</span>
        </el-form-item>
        </template>

        <el-form-item label="数据集划分">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="当前训练数据会随机划分为 80% 训练集、20% 验证集；不单独生成 test 集。"
          />
        </el-form-item>

        <el-form-item label="复训策略">
          <el-checkbox v-model="trainingConfig.forceRetrain">
            允许无新增数据时复训同一批数据
          </el-checkbox>
          <span class="form-hint">默认会阻止与上次完成训练完全相同的数据集，避免误重复训练。</span>
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
    <el-card class="panel">
      <template #header>
        <div class="history-header">
          <span class="card-title">模型测试</span>
          <el-select
            v-model="selectedTestModelId"
            placeholder="选择测试模型"
            filterable
            clearable
            style="width: 360px;"
            @change="handleTestModelChange">
            <el-option
              v-for="model in completedModels"
              :key="model.id"
              :label="testModelLabel(model)"
              :value="model.id" />
          </el-select>
        </div>
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
              <div class="test-image-preview">
                <img v-show="!detectionCanvasReady" :src="testImage" alt="测试图片" />
                <canvas
                  v-show="detectionCanvasReady"
                  ref="detectionCanvasRef"
                  class="detection-canvas"
                />
              </div>
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

        <el-alert
          v-if="!selectedTestModel"
          type="warning"
          :closable="false"
          show-icon
          class="test-model-alert"
          title="暂无可测试模型。训练完成后会自动选择最新模型，也可以在训练历史中手动选择。"
        />

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
  mode: 'manual',
  modelName: '',
  epochs: 100,
  batchSize: 16,
  imageSize: 640,
  device: '0',
  forceRetrain: false
})

const isStarting = ref(false)
const trainingStatus = ref(null)
const showTestSection = ref(false)
const testImage = ref(null)
const testFile = ref(null)
const isDetecting = ref(false)
const detectionResults = ref([])
const detectionCanvasRef = ref(null)
const detectionCanvasReady = ref(false)
const consoleLogs = ref([])
const consoleRef = ref(null)
const trainingHistory = ref([])
const historyLoading = ref(false)
const selectedTestModel = ref(null)
const selectedTestModelId = ref(null)

let pollInterval = null
let trainingStartTime = null
let elapsedTimerInterval = null
let lastLoggedEpoch = 0
let lastLoggedLossEpoch = 0
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
  const percentage = Math.round((trainingStatus.value.currentEpoch / trainingStatus.value.totalEpochs) * 100)
  return trainingStatus.value.status === 'TRAINING' ? Math.min(99, percentage) : percentage
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
  if (
    status === 'TRAINING' &&
    trainingStatus.value?.currentEpoch >= trainingStatus.value?.totalEpochs
  ) {
    return '最终验证与保存中'
  }
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

  try {
    isStarting.value = true
    const previewResponse = await projectAPI.getTrainingPreview(props.project.id)
    const preview = previewResponse.data || {}
    if (preview.canStart === false) {
      ElMessage.error('当前没有可用于训练的已审核标注数据')
      return
    }
    const skippedCount = preview.skippedIncrementals?.length || 0
    const reviewedCount = preview.reviewedIncrementals?.length || 0
    const syncResult = preview.syncResult || {}
    const currentPool = preview.currentRoundPoolStats || {}
    const latestTraining = preview.latestTrainingData || {}
    const previewHtml = `
      <div style="line-height:1.8;text-align:left;">
        <div>主项目任务数：<b>${preview.mainTaskCount || 0}</b>${preview.mainProjectAlive ? '' : '（Label Studio 主项目不可用）'}</div>
        <div>已全审增量任务数：<b>${preview.reviewedIncrementalTasks || 0}</b>，批次数：<b>${reviewedCount}</b></div>
        <div>将跳过未全审增量任务数：<b>${preview.pendingIncrementalTasks || 0}</b>，批次数：<b>${skippedCount}</b></div>
        <div>当前采集轮次：<b>#${preview.currentRoundId || '-'}</b>，本轮新增回流 <b>${currentPool.total || 0}</b> 张（高置信 ${currentPool.HIGH || 0}，低-A ${currentPool.LOW_A || 0}，低-B ${currentPool.LOW_B || 0}，丢弃 ${currentPool.DISCARDED || 0}）</div>
        <div>上次完成训练：${latestTraining.exists ? `<b>#${latestTraining.trainingRecordId}</b>，数据 ${latestTraining.totalImages || 0} 张 / ${latestTraining.totalAnnotations || 0} 框` : '<b>暂无</b>'}</div>
        <div>可信池：总计 <b>${syncResult.trustedTotal || 0}</b> 张，已入主 LS <b>${syncResult.trustedSyncedTotal || 0}</b> 张，待补偿 <b>${syncResult.trustedPending || 0}</b> 张</div>
        <div>本次新增同步：可信数据 <b>${syncResult.trustedSyncedThisTime || syncResult.trustedSynced || 0}</b> 张，LOW_B 入增量项目 <b>${syncResult.lowBSyncedThisTime || syncResult.lowBSynced || 0}</b> 张</div>
        <div>LOW_B 未满批等待：<b>${syncResult.waitingLowB || 0}</b> / <b>${syncResult.lowBBatchSize || 100}</b></div>
        <div>本次可用任务总数：<b>${preview.totalUsableTasks || 0}</b></div>
        ${currentPool.total === 0 && latestTraining.exists ? '<div style="color:#E6A23C;">当前轮次还没有新的边端回流数据；如继续训练，可能与上次训练数据相同并被后端拦截。</div>' : ''}
        <div style="color:#606266;">${preview.splitPolicy || '随机 80% 训练集 / 20% 验证集'}</div>
      </div>`
    await ElMessageBox.confirm(
      previewHtml,
      '确认训练',
      {
        confirmButtonText: trainingConfig.value.mode === 'automl' ? '启动 AutoML' : '开始训练',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: true
      }
    )

    consoleLogs.value = []
    lastLoggedEpoch = 0
    lastLoggedLossEpoch = 0
    addLog('正在提交训练任务...', 'info', '🚀')

    const requestConfig = {
      ...trainingConfig.value,
      autoML: trainingConfig.value.mode === 'automl'
    }
    const response = await projectAPI.startTraining(props.project.id, requestConfig)
    
    if (response.data?.status === 'FAILED') {
      trainingStatus.value = response.data
      addLog('启动训练失败: ' + (response.data.errorMessage || response.data.message || '未知错误'), 'error', '❌')
      ElMessage.error('启动训练失败：' + (response.data.errorMessage || response.data.message))
      return
    }
    
    trainingStatus.value = response.data
    
    if (trainingConfig.value.mode === 'automl') {
      addLog('AutoML 参数配置已提交，后端将按数据规模自动选择训练参数', 'info', '📋')
      if (response.data?.autoMLConfig) {
        const cfg = response.data.autoMLConfig
        addLog(`AutoML 已选择: ${cfg.modelType} | ${cfg.epochs} 轮 | batch ${cfg.batchSize} | imgsz ${cfg.imageSize}`, 'success', '✅')
      }
    } else {
      addLog(`模型: ${trainingConfig.value.modelName} | 轮数: ${trainingConfig.value.epochs} | 批次: ${trainingConfig.value.batchSize}`, 'info', '📋')
    }
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
    
    if (shouldLogEpochStatus(newStatus)) {
      const epoch = Number(newStatus.currentEpoch || 0)
      lastLoggedEpoch = epoch
      if (newStatus.trainLoss != null) {
        lastLoggedLossEpoch = epoch
      }
      addLog(`Epoch ${epoch}/${newStatus.totalEpochs} - ${formatLossStatus(newStatus)}`, 'info', '📈')
    }
  } catch (error) {
    console.error('获取训练状态失败:', error)
  }
}

const shouldLogEpochStatus = (status) => {
  if (status?.status !== 'TRAINING') return false
  const epoch = Number(status.currentEpoch || 0)
  if (!epoch || epoch <= 0) return false
  if (epoch <= lastLoggedEpoch && !(status.trainLoss != null && lastLoggedLossEpoch === 0)) {
    return false
  }

  const totalEpochs = Number(status.totalEpochs || 0)
  const hasLoss = status.trainLoss != null
  if (hasLoss && lastLoggedLossEpoch === 0) {
    return true
  }
  if (totalEpochs > 0 && epoch >= totalEpochs && epoch > lastLoggedEpoch) {
    return true
  }
  if (epoch - lastLoggedEpoch >= 10) {
    return true
  }
  return !hasLoss && lastLoggedEpoch === 0
}

const formatLossStatus = (status) => {
  if (status?.trainLoss != null) {
    const parts = [`loss: ${Number(status.trainLoss).toFixed(4)}`]
    if (status.boxLoss != null) parts.push(`box ${Number(status.boxLoss).toFixed(4)}`)
    if (status.clsLoss != null) parts.push(`cls ${Number(status.clsLoss).toFixed(4)}`)
    if (status.dflLoss != null) parts.push(`dfl ${Number(status.dflLoss).toFixed(4)}`)
    return parts.join(' · ')
  }
  return 'loss: 采集中'
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
  testImage.value = null
  detectionResults.value = []
  detectionCanvasReady.value = false
  consoleLogs.value = []
  elapsedSeconds.value = 0
  lastLoggedEpoch = 0
  lastLoggedLossEpoch = 0
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
    detectionCanvasReady.value = false
  }
  reader.readAsDataURL(file.raw)
}

const clearTestImage = () => {
  testImage.value = null
  testFile.value = null
  detectionResults.value = []
  detectionCanvasReady.value = false
}

const normalizeBbox = (bbox) => {
  if (!bbox) return null
  if (Array.isArray(bbox) && bbox.length >= 4) {
    const [x1, y1, x2, y2] = bbox.map(Number)
    return { x1, y1, x2, y2 }
  }
  if (typeof bbox === 'object') {
    const x1 = Number(bbox.x1 ?? bbox.x ?? bbox.left)
    const y1 = Number(bbox.y1 ?? bbox.y ?? bbox.top)
    const x2 = bbox.x2 != null ? Number(bbox.x2) : x1 + Number(bbox.width ?? bbox.w ?? 0)
    const y2 = bbox.y2 != null ? Number(bbox.y2) : y1 + Number(bbox.height ?? bbox.h ?? 0)
    return { x1, y1, x2, y2 }
  }
  return null
}

const drawDetectionPreview = async () => {
  if (!testImage.value) return
  await nextTick()
  const canvas = detectionCanvasRef.value
  if (!canvas) return

  const img = new Image()
  img.onload = () => {
    const ctx = canvas.getContext('2d')
    canvas.width = img.naturalWidth || img.width
    canvas.height = img.naturalHeight || img.height
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height)

    const colors = ['#e11d48', '#2563eb', '#16a34a', '#f59e0b', '#7c3aed', '#0891b2']
    detectionResults.value.forEach((det, index) => {
      const box = normalizeBbox(det.bbox)
      if (!box) return

      const x = Math.max(0, Math.min(box.x1, canvas.width))
      const y = Math.max(0, Math.min(box.y1, canvas.height))
      const width = Math.max(0, Math.min(box.x2, canvas.width) - x)
      const height = Math.max(0, Math.min(box.y2, canvas.height) - y)
      if (width <= 0 || height <= 0) return

      const color = colors[index % colors.length]
      const label = `${det.label || '目标'} ${((Number(det.confidence) || 0) * 100).toFixed(1)}%`

      ctx.strokeStyle = color
      ctx.lineWidth = Math.max(2, Math.round(canvas.width / 360))
      ctx.strokeRect(x, y, width, height)

      ctx.font = `bold ${Math.max(14, Math.round(canvas.width / 42))}px Arial`
      const paddingX = 8
      const paddingY = 5
      const textMetrics = ctx.measureText(label)
      const labelHeight = Math.max(22, Math.round(canvas.width / 28))
      const labelWidth = Math.min(canvas.width - x, textMetrics.width + paddingX * 2)
      const labelY = y >= labelHeight ? y - labelHeight : y

      ctx.fillStyle = color
      ctx.fillRect(x, labelY, labelWidth, labelHeight)
      ctx.fillStyle = '#ffffff'
      ctx.textBaseline = 'middle'
      ctx.fillText(label, x + paddingX, labelY + labelHeight / 2)
    })

    detectionCanvasReady.value = true
  }
  img.onerror = () => {
    detectionCanvasReady.value = false
    ElMessage.error('测试图片加载失败，无法绘制检测框')
  }
  img.src = testImage.value
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
    await drawDetectionPreview()
    
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
    selectedTestModelId.value = null
    return
  }

  if (preferLatestCompleted || !selectedTestModel.value) {
    selectedTestModel.value = completed[0]
    selectedTestModelId.value = completed[0].id
    return
  }

  const stillExists = completed.find(record => record.id === selectedTestModel.value.id)
  selectedTestModel.value = stillExists || completed[0]
  selectedTestModelId.value = selectedTestModel.value.id
}

const selectTestModel = (record) => {
  selectedTestModel.value = record
  selectedTestModelId.value = record.id
  detectionResults.value = []
  detectionCanvasReady.value = false
  ElMessage.success(`已切换测试模型：${record.runName || record.id}`)
}

const completedModels = computed(() => trainingHistory.value.filter(record => record.status === 'COMPLETED' && record.bestModelPath))

const testModelLabel = (model) => {
  const score = model.map50 != null ? ` · mAP@0.5 ${model.map50.toFixed(4)}` : ''
  return `#${model.id} ${model.runName || model.modelType}${score}`
}

const handleTestModelChange = (modelId) => {
  const model = completedModels.value.find(item => item.id === modelId)
  if (model) {
    selectTestModel(model)
  } else {
    selectedTestModel.value = null
    detectionResults.value = []
    detectionCanvasReady.value = false
  }
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
.test-image-preview {
  width: 100%;
  display: flex;
  justify-content: center;
}
.test-image-preview img,
.detection-canvas {
  max-width: min(640px, 100%);
  max-height: 560px;
  border-radius: 8px;
  object-fit: contain;
  box-shadow: 0 1px 8px rgba(0, 0, 0, 0.08);
}
.detection-canvas {
  width: auto;
  height: auto;
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
