<template>
  <div class="edge-simulator">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="当前使用批量图片模拟边端视频流：每张图片视为一帧，部署模型会逐帧推理并按回流策略进入高置信、低-A、低-B 或丢弃池。"
    />

    <div class="toolbar">
      <el-select v-model="selectedModelId" placeholder="选择已完成模型" filterable style="width: 360px;">
        <el-option
          v-for="model in completedModels"
          :key="model.id"
          :label="modelLabel(model)"
          :value="model.id" />
      </el-select>
      <el-input v-model="edgeNodeName" placeholder="边端节点名称" style="width: 180px;" />
      <el-button type="primary" :loading="deploying" @click="deployModel">
        <el-icon><Upload /></el-icon>
        部署
      </el-button>
      <el-button @click="refreshAll">
        <el-icon><Refresh /></el-icon>
      </el-button>
    </div>

    <el-alert
      v-if="activeDeployment"
      type="success"
      :closable="false"
      class="status-alert">
      <template #title>
        当前部署：{{ activeDeployment.edgeNodeName }} · 模型记录 #{{ activeDeployment.modelRecordId }} · 轮次 #{{ activeDeployment.roundId }}
      </template>
    </el-alert>

    <div class="stat-grid">
      <div class="stat-item">
        <span class="stat-value">{{ poolStats.highCount || 0 }}</span>
        <span class="stat-label">高置信池</span>
      </div>
      <div class="stat-item">
        <span class="stat-value">{{ poolStats.lowACount || 0 }}</span>
        <span class="stat-label">低-A 池</span>
      </div>
      <div class="stat-item">
        <span class="stat-value">{{ poolStats.lowBCount || 0 }}</span>
        <span class="stat-label">低-B 待审池</span>
      </div>
      <div class="stat-item">
        <span class="stat-value">{{ poolStats.discardedCount || 0 }}</span>
        <span class="stat-label">丢弃</span>
      </div>
    </div>

    <el-upload
      drag
      multiple
      :auto-upload="false"
      :show-file-list="true"
      :on-change="handleFileChange"
      :on-remove="handleFileRemove"
      accept="image/*,.zip,application/zip,application/x-zip-compressed"
      class="edge-upload">
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">拖拽图片压缩包到此处或 <em>点击导入 zip</em></div>
      <template #tip>
        <div class="el-upload__tip">
          <span v-if="selectedArchive">已选择压缩包：{{ selectedArchive.name }}，后端会自动解析其中所有图片。</span>
          <span v-else>已选择 {{ selectedFiles.length }} 帧。点击推理后会使用当前部署模型逐帧检测，并写入当前轮次的数据池。</span>
        </div>
      </template>
    </el-upload>

    <div v-if="uploadSummary.hasSelection" class="upload-summary">
      <div class="summary-header">
        <span>本次模拟输入</span>
        <el-tag v-if="uploadSummary.type === 'ARCHIVE'" type="warning" size="small">ZIP 压缩包</el-tag>
        <el-tag v-else type="success" size="small">图片帧序列</el-tag>
      </div>
      <div class="summary-grid">
        <div class="summary-item">
          <span class="summary-label">文件</span>
          <span class="summary-value" :title="uploadSummary.fileName">{{ uploadSummary.fileName }}</span>
        </div>
        <div class="summary-item">
          <span class="summary-label">大小</span>
          <span class="summary-value">{{ formatFileSize(uploadSummary.fileSize) }}</span>
        </div>
        <div class="summary-item">
          <span class="summary-label">图片帧</span>
          <span class="summary-value">{{ expectedFrameText }}</span>
        </div>
        <div v-if="uploadSummary.type === 'ARCHIVE'" class="summary-item">
          <span class="summary-label">非图片条目</span>
          <span class="summary-value">{{ uploadSummary.ignoredEntries }}</span>
        </div>
      </div>
      <el-alert
        v-if="uploadSummary.analysisStatus"
        :type="uploadSummary.imageCount > 0 ? 'success' : 'warning'"
        :closable="false"
        show-icon
        class="summary-alert"
        :title="uploadSummary.analysisStatus" />
    </div>

    <div v-if="inferenceProgress.visible" class="inference-progress-panel">
      <div class="progress-header">
        <div>
          <div class="progress-title">{{ inferenceProgress.message }}</div>
          <div class="progress-subtitle">
            {{ inferenceProgress.fileName || '未选择文件' }} · {{ formatFileSize(inferenceProgress.fileSize) }} ·
            预计 {{ inferenceProgress.frameCount || 0 }} 帧 · 已用时 {{ inferenceProgress.elapsedSeconds }} 秒
          </div>
        </div>
        <el-tag :type="inferenceProgress.error ? 'danger' : (inferenceProgress.stage === 'COMPLETED' ? 'success' : 'primary')" size="small">
          {{ inferenceProgress.error ? '失败' : progressStageText(inferenceProgress.stage) }}
        </el-tag>
      </div>
      <el-progress
        :percentage="inferenceProgress.percent"
        :status="inferenceProgress.error ? 'exception' : (inferenceProgress.stage === 'COMPLETED' ? 'success' : undefined)"
        :stroke-width="12" />
      <el-steps :active="inferenceStepActive" finish-status="success" simple class="progress-steps">
        <el-step title="准备" />
        <el-step title="上传" />
        <el-step title="推理/判定" />
        <el-step title="刷新结果" />
        <el-step title="完成" />
      </el-steps>
      <div class="progress-meta">
        <span>上传：{{ inferenceProgress.uploadPercent }}%</span>
        <span>后端已返回：{{ inferenceProgress.processedCount }} / {{ inferenceProgress.frameCount || 0 }} 帧</span>
        <span v-if="inferenceProgress.resultSummary">
          高置信 {{ inferenceProgress.resultSummary.HIGH || 0 }} · 低-A {{ inferenceProgress.resultSummary.LOW_A || 0 }} ·
          低-B {{ inferenceProgress.resultSummary.LOW_B || 0 }} · 丢弃 {{ inferenceProgress.resultSummary.DISCARDED || 0 }}
        </span>
      </div>
      <el-alert
        v-if="inferenceProgress.error"
        type="error"
        :closable="false"
        show-icon
        class="progress-error"
        :title="inferenceProgress.error" />
    </div>

    <div class="actions">
      <el-radio-group v-model="inferenceMode">
        <el-radio-button label="COLLECT_AND_UPLOAD">回流采集</el-radio-button>
        <el-radio-button label="PURE_INFERENCE">纯推理</el-radio-button>
      </el-radio-group>
      <el-button
        type="primary"
        :disabled="!activeDeployment || (!selectedArchive && selectedFiles.length === 0)"
        :loading="inferencing"
        @click="runInference">
        <el-icon><Search /></el-icon>
        {{ inferenceMode === 'PURE_INFERENCE' ? '执行纯推理' : '模拟视频流推理' }}
      </el-button>
      <el-button :disabled="!currentRound?.id" :loading="judging" @click="runVlmJudge">
        <el-icon><Operation /></el-icon>
        重新判定低-A 候选
      </el-button>
    </div>

    <el-table :data="pagedDataPoints" v-loading="loadingPoints" stripe :empty-text="inferenceMode === 'PURE_INFERENCE' ? '暂无纯推理结果' : '暂无回流数据'">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="fileName" label="图片" min-width="180" show-overflow-tooltip />
      <el-table-column label="池子" width="140">
        <template #default="{ row }">
          <el-tag :type="poolTagType(row.poolType)" size="small">{{ poolText(row.poolType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="平均置信度" width="120">
        <template #default="{ row }">{{ Number(row.avgConfidence || 0).toFixed(4) }}</template>
      </el-table-column>
      <el-table-column label="检测框数" width="100">
        <template #default="{ row }">{{ row.detections?.length || 0 }}</template>
      </el-table-column>
      <el-table-column label="VLM 判定" width="120">
        <template #default="{ row }">{{ row.vlmDecision || '-' }}</template>
      </el-table-column>
      <el-table-column label="Label Studio" width="130">
        <template #default="{ row }">
          <el-tag v-if="row.lsTaskId" type="success" size="small">任务 #{{ row.lsTaskId }}</el-tag>
          <el-tag v-else-if="row.pureInference" type="info" size="small">未回传</el-tag>
          <el-tag v-else-if="row.poolType === 'LOW_B'" type="warning" size="small">待同步</el-tag>
          <span v-else style="color: var(--gray-400);">-</span>
        </template>
      </el-table-column>
      <el-table-column label="人工审核" width="120">
        <template #default="{ row }">
            <el-switch
              v-model="row.humanReviewed"
              :disabled="row.pureInference || row.poolType !== 'LOW_B'"
              @change="value => reviewPoint(row, value)" />
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>
    <div class="table-pagination">
      <el-pagination
        v-model:current-page="dataPointPage"
        v-model:page-size="dataPointPageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="displayedDataPoints.length"
        layout="total, sizes, prev, pager, next, jumper" />
    </div>

    <el-divider />

    <div class="embedded-review">
      <div class="embedded-review-header">
        <span class="card-title">增量审核</span>
        <span class="review-hint">低-B 批次进入 Label Studio 后在这里跟踪审核进度；全审完成后才会进入训练数据集。</span>
      </div>
      <IncrementalReviewCenter :project="project" />
    </div>

    <el-divider />

    <el-table :data="deployments" v-loading="loadingDeployments" stripe empty-text="暂无部署记录">
      <el-table-column prop="id" label="部署ID" width="90" />
      <el-table-column prop="edgeNodeName" label="节点" min-width="160" />
      <el-table-column prop="modelRecordId" label="模型记录" width="110" />
      <el-table-column prop="roundId" label="轮次" width="90" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="部署时间" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.deployedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button v-if="row.status !== 'ACTIVE'" type="primary" link size="small" @click="rollback(row)">
            切回
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Operation, Refresh, Search, Upload, UploadFilled } from '@element-plus/icons-vue'
import { edgeSimulatorAPI, inferenceDataPointAPI, roundAPI, trainingAPI } from '@/api'
import IncrementalReviewCenter from '@/components/IncrementalReviewCenter.vue'

const props = defineProps({
  project: { type: Object, required: true }
})

const completedModels = ref([])
const selectedModelId = ref(null)
const edgeNodeName = ref('虚拟边端-A')
const deployments = ref([])
const dataPoints = ref([])
const pureResults = ref([])
const currentRound = ref(null)
const poolStats = ref({})
const selectedFiles = ref([])
const selectedArchive = ref(null)
const uploadSummary = ref({
  hasSelection: false,
  type: 'NONE',
  fileName: '',
  fileSize: 0,
  imageCount: 0,
  totalEntries: 0,
  ignoredEntries: 0,
  analysisStatus: ''
})
const inferenceMode = ref('COLLECT_AND_UPLOAD')
const deploying = ref(false)
const inferencing = ref(false)
const judging = ref(false)
const loadingDeployments = ref(false)
const loadingPoints = ref(false)
const dataPointPage = ref(1)
const dataPointPageSize = ref(20)
const uploadInspecting = ref(false)
const uploadAnalysisToken = ref(0)
const inferenceProgress = ref({
  visible: false,
  stage: 'IDLE',
  percent: 0,
  status: '',
  message: '',
  uploadPercent: 0,
  frameCount: 0,
  processedCount: 0,
  fileName: '',
  fileSize: 0,
  startedAt: null,
  elapsedSeconds: 0,
  resultSummary: null,
  error: ''
})
let progressTimer = null

const activeDeployment = computed(() => deployments.value.find(item => item.status === 'ACTIVE'))
const displayedDataPoints = computed(() => {
  if (inferenceMode.value === 'PURE_INFERENCE' && pureResults.value.length > 0) {
    return pureResults.value
  }
  return dataPoints.value
})
const pagedDataPoints = computed(() => {
  const start = (dataPointPage.value - 1) * dataPointPageSize.value
  return displayedDataPoints.value.slice(start, start + dataPointPageSize.value)
})
const inferenceStepActive = computed(() => {
  const order = ['PREPARING', 'UPLOADING', 'SERVER_PROCESSING', 'REFRESHING', 'COMPLETED']
  const index = order.indexOf(inferenceProgress.value.stage)
  return index < 0 ? 0 : index
})
const expectedFrameText = computed(() => {
  if (!uploadSummary.value.hasSelection) return '-'
  if (uploadSummary.value.imageCount > 0) return `${uploadSummary.value.imageCount} 张`
  if (uploadSummary.value.type === 'ARCHIVE') return '等待后端解析'
  return '-'
})

const modelLabel = (model) => {
  const score = model.map50 != null ? ` · mAP ${model.map50.toFixed(4)}` : ''
  const round = model.roundId ? ` · 轮次 #${model.roundId}` : ''
  return `#${model.id} ${model.runName || model.modelType}${round}${score}`
}

const loadModels = async () => {
  const response = await trainingAPI.getTrainingRecordsByProject(props.project.id)
  completedModels.value = (response.data || []).filter(item => item.status === 'COMPLETED' && item.bestModelPath)
  if (!selectedModelId.value && completedModels.value.length > 0) {
    selectedModelId.value = completedModels.value[0].id
  }
}

const loadCurrentRound = async () => {
  const response = await roundAPI.current(props.project.id)
  currentRound.value = response.data
}

const loadDeployments = async () => {
  loadingDeployments.value = true
  try {
    const response = await edgeSimulatorAPI.deployments(props.project.id)
    deployments.value = response.data || []
  } finally {
    loadingDeployments.value = false
  }
}

const loadDataPoints = async () => {
  if (!currentRound.value?.id) return
  loadingPoints.value = true
  try {
    const response = await inferenceDataPointAPI.list(props.project.id, { roundId: currentRound.value.id })
    dataPoints.value = response.data || []
  } finally {
    loadingPoints.value = false
  }
}

const loadStats = async () => {
  if (!currentRound.value?.id) return
  const response = await edgeSimulatorAPI.poolStats(props.project.id, currentRound.value.id)
  poolStats.value = response.data || {}
}

const refreshAll = async () => {
  await Promise.all([loadModels(), loadCurrentRound(), loadDeployments()])
  await Promise.all([loadDataPoints(), loadStats()])
}

const deployModel = async () => {
  if (!selectedModelId.value) {
    ElMessage.warning('请选择已完成的训练模型')
    return
  }
  deploying.value = true
  try {
    await edgeSimulatorAPI.deploy({
      projectId: props.project.id,
      modelRecordId: selectedModelId.value,
      edgeNodeName: edgeNodeName.value
    })
    ElMessage.success('部署完成')
    await refreshAll()
  } finally {
    deploying.value = false
  }
}

const rollback = async (deployment) => {
  await edgeSimulatorAPI.rollback({ projectId: props.project.id, deploymentId: deployment.id })
  ElMessage.success('已切换部署')
  await refreshAll()
}

const handleFileChange = (_file, fileList) => {
  applySelectedUploadFiles(fileList)
}

const handleFileRemove = (_file, fileList) => {
  applySelectedUploadFiles(fileList)
}

const applySelectedUploadFiles = async (fileList) => {
  const token = ++uploadAnalysisToken.value
  const files = fileList.map(item => item.raw).filter(Boolean)
  const archive = files.find(file => file.name?.toLowerCase().endsWith('.zip'))
  selectedArchive.value = archive || null
  selectedFiles.value = archive
    ? []
    : files.filter(file => file.type?.startsWith('image/') || /\.(jpg|jpeg|png|bmp|webp)$/i.test(file.name || ''))

  uploadSummary.value = buildInitialUploadSummary(archive, selectedFiles.value)
  if (!archive) {
    return
  }

  uploadInspecting.value = true
  try {
    const zipInfo = await inspectZipArchive(archive)
    if (token !== uploadAnalysisToken.value) return
    uploadSummary.value = {
      ...uploadSummary.value,
      imageCount: zipInfo.imageCount,
      totalEntries: zipInfo.totalEntries,
      ignoredEntries: zipInfo.ignoredEntries,
      analysisStatus: zipInfo.imageCount > 0
        ? `已识别 ${zipInfo.imageCount} 张图片，后端会按这些图片逐帧推理。`
        : '未能在 zip 中识别图片，请确认压缩包内包含 jpg/png/bmp/webp。'
    }
  } catch (error) {
    if (token !== uploadAnalysisToken.value) return
    uploadSummary.value = {
      ...uploadSummary.value,
      analysisStatus: '前端无法解析该 zip，后端仍会尝试解压并识别图片。'
    }
  } finally {
    if (token === uploadAnalysisToken.value) {
      uploadInspecting.value = false
    }
  }
}

const runInference = async () => {
  if (!activeDeployment.value) {
    ElMessage.warning('请先部署模型')
    return
  }
  const deploymentId = activeDeployment.value.id
  const frameCount = uploadSummary.value.imageCount || selectedFiles.value.length || 0
  if (frameCount <= 0) {
    ElMessage.warning('请先选择图片或包含图片的 zip 压缩包')
    return
  }
  startInferenceProgress()
  inferencing.value = true
  try {
    const submitResponse = await submitInferenceJobWithRetry(deploymentId)
    const jobId = submitResponse.data?.jobId
    if (!jobId) {
      throw new Error('后端未返回推理任务 ID')
    }
    setServerProgress('QUEUED', 70, '推理任务已提交，等待算法服务资源')
    const job = await waitForInferenceJob(jobId)
    const response = { data: job.result || {} }
    setServerProgress('REFRESHING', 92, '推理完成，正在刷新数据池和统计')
    const count = response.data?.frameCount || response.data?.count || frameCount
    const syncStatus = response.data?.reviewSyncStatus
    inferenceProgress.value.processedCount = count
    inferenceProgress.value.resultSummary = summarizeInferenceResults(response.data?.results || [])
    if (inferenceMode.value === 'PURE_INFERENCE') {
      pureResults.value = (response.data?.results || []).map(toPureResultRow)
      ElMessage.success(`已完成 ${count} 帧纯推理，未回传数据`)
      if (syncStatus) console.info(syncStatus)
      completeInferenceProgress(count)
      return
    }
    pureResults.value = []
    ElMessage.success(`已完成 ${count} 帧推理，数据已进入回流池`)
    if (syncStatus) console.info(syncStatus)
    await Promise.all([loadDataPoints(), loadStats()])
    completeInferenceProgress(count)
  } catch (error) {
    failInferenceProgress(extractErrorMessage(error))
  } finally {
    inferencing.value = false
    stopProgressTimer()
  }
}

const buildInferenceFormData = () => {
  const formData = new FormData()
  if (selectedArchive.value) {
    formData.append('archive', selectedArchive.value)
  } else {
    selectedFiles.value.forEach(file => formData.append('files', file))
  }
  return formData
}

const submitInferenceJobWithRetry = async (deploymentId) => {
  const maxAttempts = 2
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      if (attempt > 1) {
        setServerProgress('UPLOADING', 5, `上传链路中断，正在重试提交 (${attempt}/${maxAttempts})`)
      }
      return await edgeSimulatorAPI.inferenceAsync(buildInferenceFormData(), {
        params: {
          deploymentId,
          mode: inferenceMode.value
        },
        timeout: 30 * 60 * 1000,
        onUploadProgress: event => updateUploadProgress(event)
      })
    } catch (error) {
      if (attempt >= maxAttempts || !isRetryableUploadError(error)) {
        throw error
      }
      await new Promise(resolve => window.setTimeout(resolve, 1200))
    }
  }
}

const isRetryableUploadError = (error) => {
  if (error?.response) return false
  const message = String(error?.message || '')
  return error?.code === 'ERR_NETWORK'
    || message.includes('Network Error')
    || message.includes('timeout')
    || message.includes('ECONNRESET')
    || message.includes('ERR_CONNECTION_RESET')
    || message.includes('ERR_EMPTY_RESPONSE')
}

const waitForInferenceJob = async (jobId) => {
  const startedAt = Date.now()
  while (true) {
    const response = await edgeSimulatorAPI.inferenceJob(jobId)
    const job = response.data || {}
    inferenceProgress.value.stage = job.stage || job.status || inferenceProgress.value.stage
    inferenceProgress.value.percent = Math.max(inferenceProgress.value.percent, Number(job.percent || 0))
    inferenceProgress.value.message = job.message || inferenceProgress.value.message
    inferenceProgress.value.processedCount = Number(job.processedCount || 0)
    if (job.frameCount) {
      inferenceProgress.value.frameCount = job.frameCount
    }
    if (job.status === 'COMPLETED') {
      return job
    }
    if (job.status === 'FAILED') {
      throw new Error(job.error || job.message || '边端推理任务失败')
    }
    if (Date.now() - startedAt > 30 * 60 * 1000) {
      throw new Error('边端推理任务超过 30 分钟未完成')
    }
    await new Promise(resolve => window.setTimeout(resolve, 2000))
  }
}

const buildInitialUploadSummary = (archive, imageFiles) => {
  if (archive) {
    return {
      hasSelection: true,
      type: 'ARCHIVE',
      fileName: archive.name || 'archive.zip',
      fileSize: archive.size || 0,
      imageCount: 0,
      totalEntries: 0,
      ignoredEntries: 0,
      analysisStatus: '正在读取 zip 目录...'
    }
  }
  const totalSize = imageFiles.reduce((sum, file) => sum + (file.size || 0), 0)
  return {
    hasSelection: imageFiles.length > 0,
    type: imageFiles.length > 0 ? 'IMAGES' : 'NONE',
    fileName: imageFiles.length === 1 ? imageFiles[0].name : `${imageFiles.length} 张图片`,
    fileSize: totalSize,
    imageCount: imageFiles.length,
    totalEntries: imageFiles.length,
    ignoredEntries: 0,
    analysisStatus: imageFiles.length > 0 ? `已选择 ${imageFiles.length} 张图片帧。` : ''
  }
}

const inspectZipArchive = async (file) => {
  const buffer = await file.arrayBuffer()
  const bytes = new Uint8Array(buffer)
  const view = new DataView(buffer)
  const decoder = new TextDecoder('utf-8')
  let imageCount = 0
  let totalEntries = 0

  for (let offset = 0; offset <= bytes.length - 46; offset++) {
    if (view.getUint32(offset, true) !== 0x02014b50) continue
    const nameLength = view.getUint16(offset + 28, true)
    const extraLength = view.getUint16(offset + 30, true)
    const commentLength = view.getUint16(offset + 32, true)
    const nameStart = offset + 46
    const nameEnd = nameStart + nameLength
    if (nameEnd > bytes.length) break
    const name = decoder.decode(bytes.subarray(nameStart, nameEnd))
    if (name && !name.endsWith('/')) {
      totalEntries++
      if (isImageFileName(name)) imageCount++
    }
    offset = nameEnd + extraLength + commentLength - 1
  }

  return {
    imageCount,
    totalEntries,
    ignoredEntries: Math.max(0, totalEntries - imageCount)
  }
}

const startInferenceProgress = () => {
  stopProgressTimer()
  const source = selectedArchive.value
    ? { fileName: selectedArchive.value.name, fileSize: selectedArchive.value.size }
    : { fileName: uploadSummary.value.fileName, fileSize: uploadSummary.value.fileSize }
  inferenceProgress.value = {
    visible: true,
    stage: 'PREPARING',
    percent: 3,
    status: '',
    message: '准备上传模拟视频流输入',
    uploadPercent: 0,
    frameCount: uploadSummary.value.imageCount || selectedFiles.value.length || 0,
    processedCount: 0,
    fileName: source.fileName || '',
    fileSize: source.fileSize || 0,
    startedAt: Date.now(),
    elapsedSeconds: 0,
    resultSummary: null,
    error: ''
  }
  progressTimer = window.setInterval(() => {
    const progress = inferenceProgress.value
    if (!progress.startedAt || progress.stage === 'COMPLETED' || progress.stage === 'FAILED') return
    progress.elapsedSeconds = Math.floor((Date.now() - progress.startedAt) / 1000)
    if (progress.stage === 'SERVER_PROCESSING' && progress.percent < 88) {
      progress.percent += progress.frameCount > 80 ? 1 : 2
    }
  }, 1000)
}

const updateUploadProgress = (event) => {
  const total = event.total || 0
  const uploadPercent = total > 0 ? Math.min(100, Math.round((event.loaded / total) * 100)) : 0
  inferenceProgress.value.stage = uploadPercent >= 100 ? 'SERVER_PROCESSING' : 'UPLOADING'
  inferenceProgress.value.uploadPercent = uploadPercent
  inferenceProgress.value.percent = uploadPercent >= 100
    ? Math.max(inferenceProgress.value.percent, 68)
    : Math.max(5, Math.min(65, Math.round(uploadPercent * 0.6)))
  inferenceProgress.value.message = uploadPercent >= 100
    ? '上传完成，后端正在解压、逐帧推理、VLM 判定和同步 LS'
    : `正在上传模拟视频流输入 ${uploadPercent}%`
}

const setServerProgress = (stage, percent, message) => {
  inferenceProgress.value.stage = stage
  inferenceProgress.value.percent = Math.max(inferenceProgress.value.percent, percent)
  inferenceProgress.value.message = message
}

const completeInferenceProgress = (count) => {
  inferenceProgress.value.stage = 'COMPLETED'
  inferenceProgress.value.percent = 100
  inferenceProgress.value.status = 'success'
  inferenceProgress.value.uploadPercent = 100
  inferenceProgress.value.processedCount = count
  inferenceProgress.value.message = '模拟视频流推理完成'
}

const failInferenceProgress = (message) => {
  inferenceProgress.value.stage = 'FAILED'
  inferenceProgress.value.percent = 100
  inferenceProgress.value.status = 'exception'
  inferenceProgress.value.error = message || '模拟视频流推理失败'
  inferenceProgress.value.message = '模拟视频流推理失败'
  ElMessage.error(inferenceProgress.value.error)
}

const stopProgressTimer = () => {
  if (progressTimer) {
    window.clearInterval(progressTimer)
    progressTimer = null
  }
}

const summarizeInferenceResults = (results) => {
  return results.reduce((acc, item) => {
    const key = item.poolType || (item.pureInference ? 'PURE' : 'UNKNOWN')
    acc[key] = (acc[key] || 0) + 1
    return acc
  }, {})
}

const extractErrorMessage = (error) => {
  if (isRetryableUploadError(error)) {
    return '上传请求连接被中断，请确认前端和后端服务都在运行后重试；如果是大 zip，建议先用少量图片验证。'
  }
  return error?.response?.data?.message || error?.message || '模拟视频流推理失败'
}

const formatFileSize = (size) => {
  const value = Number(size || 0)
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`
}

const isImageFileName = (name) => /\.(jpg|jpeg|png|bmp|webp)$/i.test(name || '')

const progressStageText = (stage) => {
  const map = {
    IDLE: '未开始',
    QUEUED: '排队中',
    PREPARING: '准备中',
    UPLOADING: '上传中',
    SERVER_PROCESSING: '推理中',
    PERSISTING: '写入中',
    VLM_JUDGING: 'VLM 判定',
    SYNCING_LABEL_STUDIO: '同步 LS',
    REFRESHING: '刷新中',
    COMPLETED: '已完成',
    FAILED: '失败'
  }
  return map[stage] || stage
}

const toPureResultRow = (item, index) => ({
  id: `pure-${Date.now()}-${index}`,
  fileName: item.file_name || item.image_path || `frame-${index + 1}`,
  detections: item.detections || [],
  avgConfidence: item.avg_confidence || 0,
  poolType: 'PURE',
  vlmDecision: '-',
  humanReviewed: false,
  pureInference: true,
  createdAt: new Date().toISOString()
})

const runVlmJudge = async () => {
  judging.value = true
  try {
    await inferenceDataPointAPI.judge(props.project.id, currentRound.value.id)
    ElMessage.success('判定完成')
    await Promise.all([loadDataPoints(), loadStats()])
  } finally {
    judging.value = false
  }
}

const reviewPoint = async (row, reviewed) => {
  await inferenceDataPointAPI.review(row.id, reviewed)
  ElMessage.success('审核状态已更新')
}

const poolText = (poolType) => {
  const map = {
    HIGH: '高置信',
    LOW_A_CANDIDATE: '低-A 候选',
    LOW_A: '低-A',
    LOW_B: '低-B',
    PURE: '纯推理',
    DISCARDED: '丢弃'
  }
  return map[poolType] || poolType || '-'
}

const poolTagType = (poolType) => {
  const map = {
    HIGH: 'success',
    LOW_A: 'primary',
    LOW_A_CANDIDATE: 'warning',
    LOW_B: 'danger',
    PURE: 'info',
    DISCARDED: 'info'
  }
  return map[poolType] || 'info'
}

const formatDateTime = (value) => {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

watch(() => props.project.id, () => {
  if (props.project.id) refreshAll()
})

watch(displayedDataPoints, () => {
  dataPointPage.value = 1
})

onMounted(refreshAll)
</script>

<style scoped>
.edge-simulator {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar,
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.status-alert {
  margin: 0;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(140px, 1fr));
  gap: 12px;
}

.stat-item {
  border: 1px solid var(--gray-200);
  border-radius: 8px;
  padding: 14px 16px;
  background: #fff;
}

.stat-value {
  display: block;
  font-size: 24px;
  font-weight: 700;
  color: var(--primary-color);
}

.stat-label {
  display: block;
  margin-top: 4px;
  color: var(--gray-600);
  font-size: 13px;
}

.edge-upload {
  width: 100%;
}

.upload-summary,
.inference-progress-panel {
  border: 1px solid var(--gray-200);
  border-radius: 8px;
  padding: 14px 16px;
  background: #fff;
}

.summary-header,
.progress-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  font-weight: 600;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(120px, 1fr));
  gap: 12px;
}

.summary-item {
  min-width: 0;
}

.summary-label {
  display: block;
  color: var(--gray-500);
  font-size: 12px;
  line-height: 18px;
}

.summary-value {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--gray-900);
  font-size: 14px;
  line-height: 22px;
}

.summary-alert,
.progress-error {
  margin-top: 12px;
}

.progress-title {
  font-size: 15px;
  line-height: 22px;
}

.progress-subtitle,
.progress-meta {
  color: var(--gray-500);
  font-size: 12px;
  line-height: 20px;
}

.progress-steps {
  margin-top: 12px;
}

.progress-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-top: 10px;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.embedded-review {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.embedded-review-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
}

.review-hint {
  color: var(--gray-500);
  font-size: 12px;
}

@media (max-width: 768px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }

  .summary-grid {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }
}
</style>
