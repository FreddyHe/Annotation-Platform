<template>
  <div class="auto-label-page" :class="{ embedded: isEmbedded }">
    <div v-if="!isEmbedded" class="standalone-header">
      <button class="back-link" type="button" @click="goBack">← 返回项目</button>
      <PageHeading eyebrow="ANNOTATION WORKSPACE" :title="project.name || '智能标注训练'" description="完成数据上传、智能预标注、人工复核和标注结果回填。" />
    </div>

    <div class="flow-shell">
      <section class="section-band">
        <div class="section-head">
          <div>
            <h2>上传数据</h2>
            <p>上传视频或图片压缩包后，系统会自动整理成可标注数据。</p>
          </div>
          <el-button size="small" @click="loadImages">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </div>

        <div class="upload-panel">
          <FileUpload :project-id="projectId" @uploaded="handleUploaded" />
        </div>
      </section>

      <section class="section-band">
        <div class="section-head">
          <div>
            <h2>检测目标</h2>
            <p>写一句话描述要检测的目标，生成类别后可直接编辑。</p>
          </div>
        </div>

        <el-input
          v-model="requirementText"
          type="textarea"
          :rows="4"
          placeholder="例如：无人机航拍场景，检测人、车、自行车、电动车、摩托车。" />

        <div v-if="labelSchema.length" class="label-chip-editor">
          <el-input
            v-for="(label, index) in labelSchema"
            :key="index"
            v-model="label.display_name"
            class="label-chip-input"
            size="large"
            clearable
            @clear="removeLabel(index)" />
          <el-button @click="addLabel">
            添加类别
          </el-button>
        </div>
        <div v-else class="empty-hint">
          生成类别后会在这里显示，可继续修改。
        </div>

        <div class="primary-actions">
          <el-button :loading="loading.requirement" @click="parseRequirement">
            <el-icon><MagicStick /></el-icon>
            生成类别
          </el-button>
          <el-button
            type="primary"
            :disabled="!canStartFlow"
            :loading="loading.flow"
            @click="startAutoLabelFlow">
            <el-icon><MagicStick /></el-icon>
            开始智能标注
          </el-button>
        </div>
      </section>

      <section class="section-band">
        <div class="section-head">
          <div>
            <h2>复核入口</h2>
            <p>预标注完成后进入智能标注复核；训练请到“训练与测试”页面操作。</p>
          </div>
          <el-button :loading="loading.labelStudio" :disabled="loading.labelStudio" @click="openLabelStudio">
            <el-icon><Link /></el-icon>
            进入智能标注
          </el-button>
        </div>

        <div v-if="job.id" class="job-strip">
          <el-tag>{{ jobStatusText(job.status) }}</el-tag>
          <span>任务 #{{ job.id }}</span>
          <span>总数 {{ job.totalImages || 0 }}</span>
          <span>已处理 {{ job.processedImages || 0 }}</span>
          <span>失败 {{ job.failedImages || 0 }}</span>
          <el-button size="small" :disabled="jobProcessing" @click="loadPredictions">
            <el-icon><View /></el-icon>
            刷新结果
          </el-button>
        </div>

        <div v-if="showProgressPanel" class="auto-progress-panel">
          <div class="auto-progress-head">
            <div>
              <strong>{{ progressDisplay.stage }}</strong>
              <span>{{ progressDisplay.message }}</span>
            </div>
            <em>{{ progressDisplay.percentLabel }}</em>
          </div>
          <el-progress
            :percentage="progressDisplay.percent"
            :stroke-width="10"
            :show-text="false" />
          <div class="auto-progress-stats">
            <span>已运行 {{ progressDisplay.elapsed }}</span>
            <span>预计剩余 {{ progressDisplay.remaining }}</span>
            <span>已处理 {{ progressDisplay.processed }} / {{ progressDisplay.total }}</span>
          </div>
        </div>

        <div v-if="reviewStats" class="metric-grid compact">
          <div class="metric">
            <span>任务数</span>
            <strong>{{ reviewStats.totalTasks || 0 }}</strong>
          </div>
          <div class="metric">
            <span>已复核</span>
            <strong>{{ reviewStats.reviewedTasks || 0 }}</strong>
          </div>
          <div class="metric">
            <span>含预标注</span>
            <strong>{{ reviewStats.tasksWithPredictions || 0 }}</strong>
          </div>
          <div class="metric">
            <span>预标注数</span>
            <strong>{{ reviewStats.totalPredictions || 0 }}</strong>
          </div>
        </div>
      </section>

      <section class="section-band">
        <div class="section-head">
          <div>
            <h2>标注结果</h2>
            <p>视频项目会按原视频展示完整回填后的标注视频。</p>
          </div>
        </div>

        <div v-if="projectVideos.length" class="annotated-video-grid">
          <div v-for="video in projectVideos" :key="video.sourceVideoId" class="annotated-video-panel">
            <video
              v-if="annotatedVideoFor(video)?.url"
              :src="annotatedVideoFor(video).url"
              controls
              playsinline
              preload="metadata"></video>
            <div v-else class="video-placeholder">完成智能标注后生成视频</div>
            <div class="video-meta">
              <span>{{ video.originalFileName }}</span>
              <span>{{ annotatedVideoFor(video)?.frameCount || video.totalFrames || '-' }} 帧</span>
              <span>{{ annotatedVideoFor(video)?.predictionCount || 0 }} 个框</span>
            </div>
          </div>
        </div>

        <ReviewResults v-if="project.id" ref="reviewResultsRef" :project="project" />
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Link,
  MagicStick,
  Refresh,
  View
} from '@element-plus/icons-vue'
import { autoLabelToModelAPI, labelStudioAPI, projectAPI } from '@/api'
import FileUpload from '@/components/FileUpload.vue'
import ReviewResults from '@/components/ReviewResults.vue'
import PageHeading from '@/components/platform-ui/PageHeading.vue'
import {
  closeLabelStudioPendingWindow,
  openLabelStudioPendingWindow,
  openLabelStudioUrl
} from '@/utils/labelStudioUrl'

const props = defineProps({
  embedded: {
    type: Boolean,
    default: false
  }
})

const route = useRoute()
const router = useRouter()
const projectId = computed(() => Number(route.params.id))
const isEmbedded = computed(() => props.embedded)
const PREDICTION_PREVIEW_LIMIT = 80

const project = ref({})
const images = ref([])
const projectVideos = ref([])
const imageTotal = ref(0)
const labelsText = ref('')
const requirementText = ref('')
const labelSchema = ref([])
const datasetProfile = ref(null)
const routePlan = ref(null)
const job = ref({})
const candidates = ref([])
const fusedPredictions = ref([])
const candidateTotal = ref(0)
const fusedPredictionTotal = ref(0)
const reviewStats = ref(null)
const reviewResultsRef = ref(null)
const annotatedVideo = ref(null)
const annotatedVideos = ref([])
let pollTimer = null
let clockTimer = null
let pollingTickRunning = false
let postProcessRunning = false
const postProcessAfterCompletion = ref(false)
const nowMs = ref(Date.now())

const loading = reactive({
  images: false,
  requirement: false,
  confirm: false,
  route: false,
  createJob: false,
  run: false,
  sync: false,
  video: false,
  flow: false,
  labelStudio: false
})

const canStartFlow = computed(() => {
  return imageTotal.value > 0 && (labelSchema.value.length > 0 || Boolean(String(requirementText.value || '').trim()))
})

const jobFusedPredictionCount = computed(() => {
  const runtime = job.value?.runtime || {}
  const runtimeCount = Number(runtime.fused_prediction_count || runtime.fusedPredictionCount || 0)
  return Math.max(runtimeCount, fusedPredictionTotal.value, fusedPredictions.value.length)
})

const hasFusedPredictions = computed(() => jobFusedPredictionCount.value > 0)

const jobBusy = computed(() => {
  return ['RUNNING', 'PROBING', 'SYNCING'].includes(job.value?.status) || loading.run || loading.sync || loading.video
})

const jobProcessing = computed(() => {
  return ['RUNNING', 'PROBING', 'SYNCING'].includes(job.value?.status)
})

const activeJobProgress = computed(() => {
  return job.value?.runtime?.auto_label_progress || job.value?.progress || {}
})

const progressDisplay = computed(() => {
  const progress = activeJobProgress.value || {}
  const total = Math.max(
    asNumber(progress.total),
    asNumber(job.value?.totalImages),
    asNumber(imageTotal.value),
    0
  )
  const processed = Math.min(
    total || Number.MAX_SAFE_INTEGER,
    Math.max(asNumber(progress.processed), asNumber(job.value?.processedImages), 0)
  )
  const elapsedSeconds = Math.max(
    asNumber(progress.elapsed_seconds),
    elapsedSecondsSince(progress.started_at || job.value?.startedAt),
    0
  )
  const hasRealtimeProgress = asNumber(progress.processed) > 0 || asNumber(progress.estimated_remaining_seconds) > 0
  const fallbackTotalSeconds = estimateAutoLabelSeconds(total)
  const rawPercent = total > 0 ? (processed / total) * 100 : 0
  const fallbackPercent = jobProcessing.value && processed === 0 && fallbackTotalSeconds > 0
    ? Math.min(95, (elapsedSeconds / fallbackTotalSeconds) * 100)
    : 0
  const percent = clampPercent(Math.max(rawPercent, fallbackPercent))
  const remainingSeconds = asNumber(progress.estimated_remaining_seconds) > 0
    ? asNumber(progress.estimated_remaining_seconds)
    : Math.max(0, fallbackTotalSeconds - elapsedSeconds)
  const stage = String(progress.stage || jobProgressStage(job.value?.status))
  const message = String(progress.message || (hasRealtimeProgress ? '系统正在持续更新处理进度' : '系统正在启动并估算进度'))
  return {
    stage,
    message,
    percent,
    percentLabel: `${percent.toFixed(percent >= 10 ? 0 : 1)}%`,
    elapsed: formatDuration(elapsedSeconds),
    remaining: remainingSeconds > 0 ? formatDuration(remainingSeconds) : (jobProcessing.value ? '计算中' : '0秒'),
    processed,
    total: total || '-'
  }
})

const showProgressPanel = computed(() => {
  return Boolean(job.value?.id) && (jobProcessing.value || loading.flow || loading.run || loading.sync)
})

const loadProject = async () => {
  const response = await projectAPI.getProjectById(projectId.value)
  project.value = response.data || {}
  labelsText.value = (project.value.labels || []).join('、')
}

const loadImages = async () => {
  loading.images = true
  try {
    const response = await projectAPI.getProjectImages(projectId.value, { page: 0, size: 50 })
    images.value = response.data?.images || []
    imageTotal.value = response.data?.pageable?.totalElements || response.data?.total || images.value.length
  } finally {
    loading.images = false
  }
}

const loadProjectVideos = async () => {
  const response = await projectAPI.getProjectVideos(projectId.value)
  projectVideos.value = response.data?.items || []
}

const loadReviewStats = async () => {
  try {
    const response = await projectAPI.getReviewStats(projectId.value)
    reviewStats.value = response.data || reviewStats.value
  } catch (error) {
    reviewStats.value = reviewStats.value || {
      totalTasks: 0,
      reviewedTasks: 0,
      pendingTasks: 0,
      tasksWithPredictions: 0,
      totalPredictions: 0
    }
  }
}

const handleUploaded = async () => {
  await Promise.all([loadProject(), loadImages(), loadProjectVideos()])
  datasetProfile.value = null
  routePlan.value = null
  job.value = {}
  candidates.value = []
  fusedPredictions.value = []
  candidateTotal.value = 0
  fusedPredictionTotal.value = 0
  reviewStats.value = null
  annotatedVideo.value = null
  annotatedVideos.value = []
  postProcessAfterCompletion.value = false
  ElMessage.success('素材已处理，图片列表已刷新')
}

const parseRequirement = async ({ silent = false } = {}) => {
  loading.requirement = true
  try {
    const response = await autoLabelToModelAPI.parseRequirement(projectId.value, {
      text: requirementText.value
    })
    labelSchema.value = normalizeLabelSchema(response.data?.item?.labelSchema || [])
    if (!silent) ElMessage.success('方案已生成')
    return labelSchema.value
  } finally {
    loading.requirement = false
  }
}

const saveConfirmedRequirement = async ({ silent = false } = {}) => {
  if (!labelSchema.value.length) return []
  loading.confirm = true
  try {
    const response = await autoLabelToModelAPI.parseRequirement(projectId.value, {
      labels: confirmedLabelNames(),
      text: requirementText.value
    })
    labelSchema.value = normalizeLabelSchema(response.data?.item?.labelSchema || [])
    if (!silent) ElMessage.success('标签方案已保存')
    return labelSchema.value
  } finally {
    loading.confirm = false
  }
}

const buildRoute = async ({ silent = false } = {}) => {
  loading.route = true
  try {
    const profileResponse = await autoLabelToModelAPI.createDatasetProfile(projectId.value)
    datasetProfile.value = profileResponse.data?.item || null
    const routeResponse = await autoLabelToModelAPI.previewRoute(projectId.value)
    routePlan.value = routeResponse.data?.item || null
    if (!silent) ElMessage.success('模型方案已生成')
    return routePlan.value
  } finally {
    loading.route = false
  }
}

const createJob = async ({ silent = false, semanticVerificationEnabled = false } = {}) => {
  loading.createJob = true
  try {
    const response = await autoLabelToModelAPI.createJob(projectId.value, {
      semanticVerificationEnabled
    })
    const item = response.data?.item || {}
    job.value = item
    candidates.value = []
    fusedPredictions.value = []
    candidateTotal.value = 0
    fusedPredictionTotal.value = 0
    annotatedVideo.value = null
    annotatedVideos.value = []
    reviewStats.value = null
    if (!silent) ElMessage.success('任务已创建')
    startPolling()
    return item
  } finally {
    loading.createJob = false
  }
}

const loadSavedAutoLabelState = async () => {
  const [requirementResult, profileResult, routeResult, jobsResult] = await Promise.allSettled([
    autoLabelToModelAPI.getLatestRequirement(projectId.value),
    autoLabelToModelAPI.getLatestDatasetProfile(projectId.value),
    autoLabelToModelAPI.getLatestRoute(projectId.value),
    autoLabelToModelAPI.listJobs(projectId.value)
  ])

  if (requirementResult.status === 'fulfilled') {
    const item = requirementResult.value.data?.item
    if (item) {
      labelSchema.value = normalizeLabelSchema(item.labelSchema || [])
      if (!requirementText.value && item.rawUserText) {
        requirementText.value = item.rawUserText
      }
      if (!labelsText.value && Array.isArray(item.rawUserLabels)) {
        labelsText.value = item.rawUserLabels.join('、')
      }
    }
  }

  if (profileResult.status === 'fulfilled') {
    datasetProfile.value = profileResult.value.data?.item || null
  }

  if (routeResult.status === 'fulfilled') {
    routePlan.value = routeResult.value.data?.item || null
  }

  if (jobsResult.status === 'fulfilled') {
    const jobs = jobsResult.value.data?.items || []
    const latestJob = jobs[0]
    if (latestJob) {
      job.value = latestJob
      annotatedVideo.value = latestJob.runtime?.annotated_video || null
      annotatedVideos.value = latestJob.runtime?.annotated_videos || []
      reviewStats.value = latestJob.runtime?.label_studio_review_stats || reviewStats.value
      if (['RUNNING', 'PROBING', 'SYNCING'].includes(latestJob.status)) {
        startPolling()
      }
      if (['COMPLETED', 'FAILED'].includes(latestJob.status)) {
        await loadPredictions()
      }
    }
  }

  loadReviewStats()

  if (job.value?.status === 'COMPLETED') {
    await ensureCompletedJobOutputs({ silent: true })
  }
}

const runJob = async (jobId = job.value?.id, { silent = false } = {}) => {
  if (!jobId) return null
  loading.run = true
  try {
    const response = await autoLabelToModelAPI.runJob(projectId.value, jobId)
    const item = response.data?.item || {}
    job.value = item || job.value
    annotatedVideo.value = job.value.runtime?.annotated_video || annotatedVideo.value
    annotatedVideos.value = job.value.runtime?.annotated_videos || annotatedVideos.value
    await loadPredictions()
    if (response.data?.skipped) {
      if (!silent) ElMessage.info(response.data?.message || '任务状态未变化')
    } else if (job.value.status === 'FAILED') {
      ElMessage.error(job.value.errorMessage || '自动标注失败')
    } else {
      if (!silent) ElMessage.success('自动标注已完成')
    }
    startPolling()
    return job.value
  } catch (error) {
    if (isBackgroundRunDisconnect(error)) {
      postProcessAfterCompletion.value = true
      startPolling()
      if (!silent) {
        ElMessage.warning('智能标注仍在后台执行，页面会自动刷新结果')
      }
      return job.value
    }
    throw error
  } finally {
    loading.run = false
  }
}

const renderAnnotatedVideo = async ({ silent = false } = {}) => {
  if (!job.value?.id) return null
  loading.video = true
  try {
    const response = await autoLabelToModelAPI.renderAnnotatedVideo(projectId.value, job.value.id)
    applyAnnotatedVideoResponse(response.data)
    await loadProjectVideos()
    if (response.data?.available === false) {
      if (!silent) ElMessage.warning(response.data?.reason || '暂时没有可生成的视频')
    } else if (!silent) {
      ElMessage.success('标注视频已生成')
    }
    return annotatedVideo.value
  } catch (error) {
    if (isBackgroundRunDisconnect(error)) {
      const response = await autoLabelToModelAPI.getAnnotatedVideo(projectId.value, job.value.id)
      applyAnnotatedVideoResponse(response.data)
      await loadProjectVideos()
      const recovered = response.data?.available !== false &&
        (annotatedVideos.value.length > 0 || Boolean(annotatedVideo.value))
      if (recovered) {
        if (!silent) ElMessage.success('标注视频已生成')
        return annotatedVideo.value
      }
      if (!silent) ElMessage.warning('标注视频仍在后台生成，请稍后刷新结果')
      return null
    }
    if (!silent) {
      ElMessage.error(error?.response?.data?.message || error?.message || '标注视频生成失败')
    }
    return null
  } finally {
    loading.video = false
  }
}

const applyAnnotatedVideoResponse = data => {
  job.value = data?.item || job.value
  annotatedVideo.value = data?.annotatedVideo || job.value?.runtime?.annotated_video || null
  annotatedVideos.value = data?.annotatedVideos || job.value?.runtime?.annotated_videos || []
}

const loadPredictions = async () => {
  if (!job.value?.id) return
  const [candidateResponse, fusedResponse] = await Promise.all([
    autoLabelToModelAPI.listCandidates(projectId.value, job.value.id, { page: 0, size: 20 }),
    autoLabelToModelAPI.listFusedPredictions(projectId.value, job.value.id, { page: 0, size: PREDICTION_PREVIEW_LIMIT })
  ])
  candidates.value = candidateResponse.data?.items || []
  fusedPredictions.value = fusedResponse.data?.items || []
  candidateTotal.value = Number(candidateResponse.data?.total || candidates.value.length || 0)
  fusedPredictionTotal.value = Number(fusedResponse.data?.total || fusedPredictions.value.length || 0)
}

const syncLabelStudio = async ({ silent = false } = {}) => {
  if (!job.value?.id) return
  loading.sync = true
  try {
    const response = await autoLabelToModelAPI.syncLabelStudio(projectId.value, job.value.id)
    job.value = response.data?.item || job.value
    reviewStats.value = response.data?.reviewStats || null
    const lsProjectId = response.data?.labelStudioProjectId
    if (lsProjectId) {
      project.value = {
        ...project.value,
        labelStudioProjectId: lsProjectId,
        lsProjectId
      }
    }
    const imported = Number(response.data?.importStats?.success || response.data?.syncedCount || 0)
    const existingPredictions = Number(reviewStats.value?.totalPredictions || 0)
    if (imported > 0 || existingPredictions > 0) {
      if (!silent) ElMessage.success('已同步到复核入口')
    } else if (!silent) {
      ElMessage.warning(job.value.errorMessage || '未导入任何预标注，请检查同步结果')
    }
    await refreshReviewSurface()
    return response.data
  } finally {
    loading.sync = false
  }
}

const refreshReviewSurface = async () => {
  if (!project.value?.id) return
  try {
    const statsResponse = await projectAPI.getReviewStats(projectId.value)
    reviewStats.value = statsResponse.data || reviewStats.value
  } catch (error) {
    // The review panel has its own visible error handling; keep the flow moving here.
  }
  await reviewResultsRef.value?.refresh?.()
}

const isBackgroundRunDisconnect = error => {
  return !error.response ||
    error.code === 'ECONNABORTED' ||
    error.response?.status === 502 ||
    error.response?.data?.errorCode === 'PROXY_ERROR'
}

const ensureCompletedJobOutputs = async ({ silent = false } = {}) => {
  if (!job.value?.id || job.value.status !== 'COMPLETED' || postProcessRunning) return
  postProcessRunning = true
  try {
    if (!hasFusedPredictions.value) {
      await loadPredictions()
    }
    if (!hasFusedPredictions.value) return

    const runtimeReviewStats = job.value?.runtime?.label_studio_review_stats || null
    const runtimeImportStats = job.value?.runtime?.label_studio_import_stats || null
    if (!reviewStats.value && runtimeReviewStats) {
      reviewStats.value = runtimeReviewStats
    }
    const hasReviewData = Number(reviewStats.value?.totalTasks || 0) > 0 ||
      Number(reviewStats.value?.totalPredictions || 0) > 0 ||
      Number(runtimeReviewStats?.totalTasks || 0) > 0 ||
      Number(runtimeReviewStats?.totalPredictions || 0) > 0 ||
      Number(runtimeImportStats?.success || 0) > 0
    if (!hasReviewData) {
      try {
        await syncLabelStudio({ silent: true })
      } catch (error) {
        if (!silent) throw error
        return
      }
    }

    const runtimeVideos = job.value?.runtime?.annotated_videos || []
    const hasRenderedVideos = annotatedVideos.value.length > 0 || runtimeVideos.length > 0
    if (postProcessAfterCompletion.value && !hasRenderedVideos && projectVideos.value.length > 0) {
      await renderAnnotatedVideo({ silent: true })
    }

    if (!silent) {
      ElMessage.success('智能标注已完成，结果已进入复核和标注视频查看入口')
    }
  } finally {
    postProcessRunning = false
    postProcessAfterCompletion.value = false
    loading.flow = false
  }
}

const startAutoLabelFlow = async () => {
  if (!canStartFlow.value) {
    ElMessage.warning('请先上传数据并填写检测目标')
    return
  }
  loading.flow = true
  try {
    if (!labelSchema.value.length) {
      await parseRequirement({ silent: true })
    } else {
      await saveConfirmedRequirement({ silent: true })
    }
    if (!labelSchema.value.length) {
      ElMessage.warning('请先生成至少一个标签')
      return
    }
    await buildRoute({ silent: true })
    if (!routePlan.value) {
      ElMessage.warning('模型方案生成失败，请稍后重试')
      return
    }
    const createdJob = await createJob({ silent: true, semanticVerificationEnabled: false })
    postProcessAfterCompletion.value = true
    await runJob(createdJob?.id, { silent: true })
    if (job.value?.status === 'COMPLETED') {
      await ensureCompletedJobOutputs({ silent: true })
      ElMessage.success('智能标注已完成，结果已进入复核和标注视频查看入口')
    } else {
      ElMessage.info('智能标注已开始，完成后会自动生成复核数据和标注视频')
    }
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || error?.message || '智能标注流程失败')
  } finally {
    if (!postProcessAfterCompletion.value) {
      loading.flow = false
    }
  }
}

const annotatedVideoFor = video => {
  const items = annotatedVideos.value.length
    ? annotatedVideos.value
    : (job.value?.runtime?.annotated_videos || [])
  const runtimeMatch = items.find(item => item.sourceVideoId === video.sourceVideoId && item.mode === 'sampled_keyframe_video') ||
    items.find(item => item.sourceVideoId === video.sourceVideoId)
  const videoOutputs = video.annotatedVideos || []
  return runtimeMatch ||
    videoOutputs.find(item => item.sourceVideoId === video.sourceVideoId && item.mode === 'sampled_keyframe_video') ||
    videoOutputs.find(item => item.sourceVideoId === video.sourceVideoId) ||
    null
}

const addLabel = () => {
  labelSchema.value.push({
    canonical_name: '',
    display_name: '',
    english_name: '',
    model_prompt: '',
    description: '',
    positive_prompts_en: [],
    positive_prompts_text: '',
    negative_prompts_text: ''
  })
}

const removeLabel = index => {
  labelSchema.value.splice(index, 1)
}

const openLabelStudio = async () => {
  if (loading.labelStudio) return

  const pendingWindow = openLabelStudioPendingWindow()
  loading.labelStudio = true
  try {
    const response = await labelStudioAPI.getLoginUrl({ projectId: projectId.value })
    if (!openLabelStudioUrl(response.data, pendingWindow)) {
      closeLabelStudioPendingWindow(pendingWindow)
      ElMessage.error('未能获取星目智能标注入口')
    }
  } catch (error) {
    closeLabelStudioPendingWindow(pendingWindow)
    ElMessage.error(error?.response?.data?.message || '打开星目智能标注失败，请稍后重试')
  } finally {
    loading.labelStudio = false
  }
}

const goBack = () => {
  router.push(`/projects/${projectId.value}`)
}

const normalizeLabelSchema = schema => {
  return schema.map(item => ({
    ...item,
    display_name: item.display_name || item.displayName || item.canonical_name || '',
    english_name: item.english_name || item.englishName || '',
    model_prompt: item.model_prompt || item.modelPrompt || item.english_prompt || item.englishPrompt || '',
    description: item.description || '',
    positive_prompts_text: (item.positive_prompts || item.positivePrompts || []).join('、'),
    positive_prompts_en: item.positive_prompts_en || item.positivePromptsEn || [],
    negative_prompts_text: (item.negative_prompts || item.negativePrompts || []).join('、')
  }))
}

const buildConfirmedLabelSchema = () => {
  return labelSchema.value
    .map(item => {
      const displayName = String(item.display_name || '').trim()
      return {
        canonical_name: item.canonical_name || displayName,
        display_name: displayName,
        english_name: item.english_name || item.englishName || '',
        model_prompt: item.model_prompt || item.modelPrompt || item.english_prompt || item.englishPrompt || '',
        description: item.description || '',
        positive_prompts_en: item.positive_prompts_en || item.positivePromptsEn || [],
        positive_prompts: splitLabels(item.positive_prompts_text || item.positive_prompts?.join('、') || displayName),
        negative_prompts: splitLabels(item.negative_prompts_text || item.negative_prompts?.join('、') || ''),
        requires_relation_reasoning: Boolean(item.requires_relation_reasoning || item.requiresRelationReasoning)
      }
    })
    .filter(item => item.display_name)
}

const confirmedLabelNames = () => {
  return buildConfirmedLabelSchema().map(item => item.display_name).filter(Boolean)
}

const splitLabels = text => {
  return String(text || '')
    .split(/[,，;；、\n]+/)
    .map(item => item.trim())
    .filter(Boolean)
}

const jobStatusText = status => {
  const map = {
    CREATED: '待执行',
    PROBING: '小样本检查中',
    RUNNING: '标注中',
    SYNCING: '同步中',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消'
  }
  return map[status] || '待确认'
}

const jobProgressStage = status => {
  const map = {
    CREATED: '等待开始',
    PROBING: '正在检查样本',
    RUNNING: '正在智能标注',
    SYNCING: '正在同步复核数据',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消'
  }
  return map[status] || '正在处理'
}

const asNumber = value => {
  const number = Number(value)
  return Number.isFinite(number) ? number : 0
}

const clampPercent = value => {
  if (!Number.isFinite(value)) return 0
  return Math.max(0, Math.min(100, value))
}

const elapsedSecondsSince = value => {
  if (!value) return 0
  const raw = String(value)
  const hasTimezone = /([zZ]|[+-]\d{2}:\d{2})$/.test(raw)
  const parsed = Date.parse(hasTimezone ? raw : `${raw}Z`)
  if (!Number.isFinite(parsed)) return 0
  return Math.max(0, (nowMs.value - parsed) / 1000)
}

const estimateAutoLabelSeconds = total => {
  if (!total) return 0
  const labelFactor = Math.max(1, labelSchema.value.length || 1)
  return Math.max(60, total * (1.2 + Math.min(labelFactor, 6) * 0.25))
}

const formatDuration = seconds => {
  const safeSeconds = Math.max(0, Math.round(asNumber(seconds)))
  const hours = Math.floor(safeSeconds / 3600)
  const minutes = Math.floor((safeSeconds % 3600) / 60)
  const secs = safeSeconds % 60
  if (hours > 0) return `${hours}小时${minutes}分`
  if (minutes > 0) return `${minutes}分${secs}秒`
  return `${secs}秒`
}

const startPolling = () => {
  stopPolling()
  if (!job.value?.id) return
  pollTimer = setInterval(async () => {
    if (pollingTickRunning) return
    pollingTickRunning = true
    try {
      const response = await autoLabelToModelAPI.getJob(projectId.value, job.value.id)
      job.value = response.data?.item || job.value
      annotatedVideo.value = job.value.runtime?.annotated_video || annotatedVideo.value
      annotatedVideos.value = job.value.runtime?.annotated_videos || annotatedVideos.value
      if (!['RUNNING', 'PROBING', 'SYNCING'].includes(job.value.status)) {
        stopPolling()
        await loadPredictions()
        if (job.value.status === 'COMPLETED' && postProcessAfterCompletion.value) {
          await ensureCompletedJobOutputs({ silent: true })
          ElMessage.success('智能标注已完成，结果已进入复核和标注视频查看入口')
        } else {
          loading.flow = false
        }
      }
    } finally {
      pollingTickRunning = false
    }
  }, 3000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onMounted(async () => {
  clockTimer = setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
  await Promise.all([loadProject(), loadImages(), loadProjectVideos()])
  await loadSavedAutoLabelState()
})

onUnmounted(() => {
  stopPolling()
  if (clockTimer) {
    clearInterval(clockTimer)
    clockTimer = null
  }
})
</script>

<style scoped>
.auto-label-page {
  min-width: 0;
}

.auto-label-page.embedded .flow-shell {
  margin-top: 0;
}

.standalone-header { margin-bottom: 18px; }

.back-link {
  margin: 0 0 16px;
  padding: 0;
  color: var(--brand-700);
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  background: none;
  border: 0;
}

.flow-shell {
  margin-top: 18px;
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-lg);
  padding: 18px;
}

.section-band {
  border-top: 0.5px solid var(--gray-200);
  padding: 18px 0;
}

.section-band:first-of-type {
  margin-top: 18px;
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 14px;
}

.section-head h2 {
  margin: 0;
  font-size: 16px;
  line-height: 24px;
  color: var(--gray-900);
}

.section-head p {
  margin: 4px 0 0;
  color: var(--gray-500);
  font-size: 13px;
}

.upload-panel {
  margin-bottom: 16px;
  padding: 14px;
  border: 1px solid var(--gray-200);
  border-radius: 12px;
  background: var(--gray-50);
}

.image-strip {
  min-height: 86px;
  display: flex;
  align-items: center;
  gap: 10px;
  overflow-x: auto;
}

.image-thumb {
  width: 76px;
  height: 76px;
  flex: 0 0 auto;
  border: 0.5px solid var(--gray-200);
  border-radius: 8px;
  overflow: hidden;
  background: var(--gray-50);
}

.image-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.metric-grid {
  display: grid;
  gap: 14px;
}

.label-chip-editor {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
}

.label-chip-input {
  width: 180px;
}

.empty-hint {
  margin-top: 10px;
  color: var(--gray-500);
  font-size: 13px;
}

.metric-grid {
  grid-template-columns: repeat(4, minmax(130px, 1fr));
}

.metric-grid.compact {
  grid-template-columns: repeat(4, minmax(120px, 1fr));
}

.metric {
  border: 1px solid var(--gray-200);
  border-radius: 12px;
  padding: 12px;
}

.metric span {
  display: block;
  color: var(--gray-500);
  font-size: 12px;
}

.metric strong {
  display: block;
  margin-top: 6px;
  color: var(--gray-900);
  font-size: 18px;
}

.route-alert {
  margin-top: 12px;
}

.primary-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 14px;
}

.action-row,
.job-strip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.job-strip {
  color: var(--gray-600);
  font-size: 13px;
}

.auto-progress-panel {
  margin-top: 14px;
  padding: 14px;
  border: 0.5px solid var(--gray-200);
  border-radius: 8px;
  background: var(--gray-50);
}

.auto-progress-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 14px;
  margin-bottom: 10px;
}

.auto-progress-head div {
  display: grid;
  gap: 4px;
}

.auto-progress-head strong {
  color: var(--gray-900);
  font-size: 14px;
}

.auto-progress-head span,
.auto-progress-stats {
  color: var(--gray-500);
  font-size: 13px;
}

.auto-progress-head em {
  color: var(--gray-900);
  font-style: normal;
  font-weight: 700;
  white-space: nowrap;
}

.auto-progress-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 10px;
}

.annotated-video-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.annotated-video-panel {
  display: grid;
  gap: 10px;
}

.annotated-video-panel video {
  width: 100%;
  max-height: 520px;
  display: block;
  border: 0.5px solid var(--gray-200);
  border-radius: 8px;
  background: #000;
}

.video-placeholder {
  min-height: 220px;
  display: grid;
  place-items: center;
  border: 0.5px solid var(--gray-200);
  border-radius: 8px;
  color: var(--gray-500);
  background: var(--gray-50);
}

.video-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--gray-500);
  font-size: 13px;
}

@media (max-width: 1100px) {
  .metric-grid,
  .metric-grid.compact {
    grid-template-columns: 1fr;
  }
}
</style>
