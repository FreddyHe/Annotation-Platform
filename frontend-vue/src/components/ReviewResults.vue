<template>
  <div class="review-results">
    <div class="review-progress-panel">
      <div class="review-progress-head">
        <span class="card-title">审核进度</span>
        <el-button @click="loadReviewStats" :loading="loading" size="small">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </div>
      <el-progress :percentage="reviewProgress" :status="reviewProgress === 100 ? 'success' : undefined" :stroke-width="24" />
      <div class="progress-info">
        <span>已审核: {{ stats.reviewedTasks }} / {{ stats.totalTasks }}</span>
        <span>待审核: {{ stats.pendingTasks }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { projectAPI } from '@/api'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'

const props = defineProps({ 
  project: { type: Object, required: true } 
})

const loading = ref(false)
const stats = ref({
  totalTasks: 0,
  reviewedTasks: 0,
  pendingTasks: 0,
  tasksWithPredictions: 0,
  totalPredictions: 0,
  totalPredictionResults: 0,
  totalAnnotationResults: 0
})

const reviewProgress = computed(() => {
  if (stats.value.totalTasks === 0) return 0
  return Math.round((stats.value.reviewedTasks / stats.value.totalTasks) * 100)
})

const loadReviewStats = async () => {
  if (!props.project || !props.project.id || !labelStudioProjectId.value) {
    return
  }
  
  try {
    loading.value = true
    const response = await projectAPI.getReviewStats(props.project.id)
    stats.value = {
      ...stats.value,
      ...response.data
    }
  } catch (error) {
    ElMessage.error('加载审核统计失败：' + (error.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

const labelStudioProjectId = computed(() => props.project?.labelStudioProjectId || props.project?.lsProjectId || null)

onMounted(() => {
  loadReviewStats()
})

watch(labelStudioProjectId, (current, previous) => {
  if (current && current !== previous) {
    loadReviewStats()
  }
})

const refresh = async () => {
  await loadReviewStats()
}

defineExpose({ refresh })
</script>

<style scoped>
.review-results { }

.review-progress-panel {
  border: 0.5px solid var(--gray-200);
  border-radius: 8px;
  padding: 16px;
}

.review-progress-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.card-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--gray-900);
}

.progress-info {
  display: flex;
  justify-content: space-between;
  margin-top: 16px;
  font-size: 13px;
  color: var(--gray-600);
}

</style>
