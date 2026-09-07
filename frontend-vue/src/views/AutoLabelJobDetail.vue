<template>
  <div class="job-detail-page">
    <el-page-header @back="goBack" :content="`自动标注任务 ${jobId}`">
      <template #extra>
        <el-button @click="openWorkflow">
          <el-icon><Aim /></el-icon>
          返回流程
        </el-button>
        <el-button type="primary" @click="refreshAll">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </template>
    </el-page-header>

    <div class="detail-shell">
      <section class="summary-band">
        <div class="status-card">
          <span>任务状态</span>
          <strong>{{ jobStatusText(job.status) }}</strong>
        </div>
        <div class="status-card">
          <span>总图片</span>
          <strong>{{ job.totalImages || 0 }}</strong>
        </div>
        <div class="status-card">
          <span>已处理</span>
          <strong>{{ job.processedImages || 0 }}</strong>
        </div>
        <div class="status-card">
          <span>失败</span>
          <strong>{{ job.failedImages || 0 }}</strong>
        </div>
      </section>

      <section class="section-band">
        <div class="section-head">
          <div>
            <h2>任务操作</h2>
            <p>固定使用质量优先自动标注，不提供策略切换。</p>
          </div>
          <div class="action-row">
            <el-button type="success" :disabled="jobBusy" :loading="loading.run" @click="runJob">
              开始标注
            </el-button>
            <el-button type="primary" :disabled="fusedPredictions.length === 0 || jobBusy" :loading="loading.sync" @click="syncLabelStudio">
              同步到星目智能标注
            </el-button>
            <el-button :loading="loading.train" @click="trainFromReviewed">
              用标注结果训练模型
            </el-button>
          </div>
        </div>
        <el-alert v-if="job.errorMessage" type="warning" :closable="false" :title="job.errorMessage" />
      </section>

      <section class="section-band">
        <div class="section-head">
          <div>
            <h2>模型调用情况</h2>
            <p>模型不可用时展示降级状态，不表示页面异常。</p>
          </div>
        </div>
        <div class="runtime-grid">
          <div class="runtime-item">
            <span>候选数量</span>
            <strong>{{ candidates.length }}</strong>
          </div>
          <div class="runtime-item">
            <span>融合数量</span>
            <strong>{{ fusedPredictions.length }}</strong>
          </div>
          <div class="runtime-item">
            <span>融合状态</span>
            <strong>{{ job.runtime?.fusion_status || '-' }}</strong>
          </div>
          <div class="runtime-item">
            <span>同步数量</span>
            <strong>{{ syncedCount }}</strong>
          </div>
        </div>
      </section>

      <section class="section-band">
        <div class="section-head">
          <div>
            <h2>融合结果</h2>
            <p>展示类别、来源模型、人审优先级和置信度。</p>
          </div>
        </div>
        <el-table :data="fusedPredictions" border>
          <el-table-column prop="imageId" label="图片" width="90" />
          <el-table-column prop="label" label="类别" width="140" />
          <el-table-column label="来源模型" min-width="180">
            <template #default="{ row }">{{ sourceText(row.sourceModels) }}</template>
          </el-table-column>
          <el-table-column label="人审优先级" width="160">
            <template #default="{ row }">
              <el-tag :type="priorityTag(row.reviewPriority)">{{ priorityText(row.reviewPriority) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="置信度" width="110">
            <template #default="{ row }">{{ scoreText(row.finalScore) }}</template>
          </el-table-column>
          <el-table-column label="融合说明" min-width="220">
            <template #default="{ row }">{{ fusionText(row.fusionReason) }}</template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Aim, Refresh } from '@element-plus/icons-vue'
import { autoLabelToModelAPI } from '@/api'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => Number(route.params.id))
const jobId = computed(() => Number(route.params.jobId))

const job = ref({})
const candidates = ref([])
const fusedPredictions = ref([])
const syncedCount = ref(0)
let pollTimer = null

const loading = reactive({
  run: false,
  sync: false,
  train: false
})

const jobBusy = computed(() => ['RUNNING', 'PROBING', 'SYNCING'].includes(job.value?.status) || loading.run || loading.sync)

const refreshAll = async () => {
  const [jobResponse, candidatesResponse, fusedResponse] = await Promise.all([
    autoLabelToModelAPI.getJob(projectId.value, jobId.value),
    autoLabelToModelAPI.listCandidates(projectId.value, jobId.value),
    autoLabelToModelAPI.listFusedPredictions(projectId.value, jobId.value)
  ])
  job.value = jobResponse.data?.item || {}
  candidates.value = candidatesResponse.data?.items || []
  fusedPredictions.value = fusedResponse.data?.items || []
  syncedCount.value = fusedPredictions.value.filter(item => item.syncedToLabelStudio).length
}

const runJob = async () => {
  loading.run = true
  try {
    const response = await autoLabelToModelAPI.runJob(projectId.value, jobId.value)
    job.value = response.data?.item || job.value
    await refreshAll()
    if (response.data?.skipped) {
      ElMessage.info(response.data?.message || '任务状态未变化')
    } else if (job.value.status === 'FAILED') {
      ElMessage.error(job.value.errorMessage || '自动标注失败')
    } else {
      ElMessage.success('自动标注已完成')
    }
    startPolling()
  } finally {
    loading.run = false
  }
}

const syncLabelStudio = async () => {
  loading.sync = true
  try {
    const response = await autoLabelToModelAPI.syncLabelStudio(projectId.value, jobId.value)
    job.value = response.data?.item || job.value
    syncedCount.value = response.data?.syncedCount || syncedCount.value
    await refreshAll()
    const imported = Number(response.data?.importStats?.success || response.data?.syncedCount || 0)
    const totalPredictions = Number(response.data?.reviewStats?.totalPredictions || 0)
    if (imported > 0 || totalPredictions > 0) {
      ElMessage.success('已同步到星目智能标注')
    } else {
      ElMessage.warning(job.value.errorMessage || '未导入任何预标注，请检查同步结果')
    }
  } finally {
    loading.sync = false
  }
}

const trainFromReviewed = async () => {
  loading.train = true
  try {
    const response = await autoLabelToModelAPI.trainFromReviewedLabels(projectId.value, {
      jobId: jobId.value,
      startTraining: true,
      epochs: 1,
      batchSize: 2,
      imageSize: 640,
      modelType: 'yolov8n.pt',
      device: '0'
    })
    if (response.data?.available === false) {
      ElMessage.warning(response.data?.reason || response.data?.message || '当前还没有可用于训练的标注框')
    } else {
      ElMessage.success('已提交 GPU 训练')
    }
  } finally {
    loading.train = false
  }
}

const openWorkflow = () => {
  router.push(`/projects/${projectId.value}/auto-label`)
}

const goBack = () => {
  router.push(`/projects/${projectId.value}`)
}

const startPolling = () => {
  stopPolling()
  pollTimer = setInterval(async () => {
    await refreshAll()
    if (!['RUNNING', 'PROBING', 'SYNCING'].includes(job.value.status)) {
      stopPolling()
    }
  }, 3000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
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

const sourceText = sources => (sources || []).join('、') || '系统模型'
const scoreText = score => (Number(score || 0) * 100).toFixed(1) + '%'
const priorityText = priority => ({ HIGH: '建议人工优先复核', MEDIUM: '需要进一步确认', LOW: '可常规复核' }[priority] || '需要进一步确认')
const priorityTag = priority => ({ HIGH: 'warning', MEDIUM: 'info', LOW: 'success' }[priority] || 'info')
const fusionText = reason => {
  const map = {
    single_candidate_manual_review_recommended: '单模型候选，建议人工优先复核',
    single_candidate_kept: '单模型候选已保留'
  }
  return map[reason] || '模型结果存在分歧时已按质量优先处理'
}

onMounted(refreshAll)
onUnmounted(stopPolling)
</script>

<style scoped>
.detail-shell {
  margin-top: 18px;
  background: #fff;
  border: 0.5px solid var(--gray-200);
  border-radius: 8px;
  padding: 18px;
}

.summary-band,
.runtime-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(120px, 1fr));
  gap: 14px;
}

.status-card,
.runtime-item {
  border: 0.5px solid var(--gray-200);
  border-radius: 8px;
  padding: 12px;
}

.status-card span,
.runtime-item span {
  display: block;
  color: var(--gray-500);
  font-size: 12px;
}

.status-card strong,
.runtime-item strong {
  display: block;
  margin-top: 6px;
  color: var(--gray-900);
  font-size: 18px;
}

.section-band {
  border-top: 0.5px solid var(--gray-200);
  padding: 18px 0;
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
}

.section-head p {
  margin: 4px 0 0;
  color: var(--gray-500);
  font-size: 13px;
}

.action-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

@media (max-width: 900px) {
  .summary-band,
  .runtime-grid {
    grid-template-columns: 1fr;
  }
}
</style>
