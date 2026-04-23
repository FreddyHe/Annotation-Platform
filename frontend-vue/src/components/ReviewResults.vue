<template>
  <div class="review-results">
    <div class="stats-overview">
      <el-card shadow="never" class="stat-card">
        <el-statistic title="总任务数" :value="stats.totalTasks">
          <template #prefix><el-icon><Document /></el-icon></template>
        </el-statistic>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <el-statistic title="已审核" :value="stats.reviewedTasks">
          <template #prefix><el-icon><CircleCheck /></el-icon></template>
        </el-statistic>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <el-statistic title="待审核" :value="stats.pendingTasks">
          <template #prefix><el-icon><Clock /></el-icon></template>
        </el-statistic>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <el-statistic title="审核进度" :value="reviewProgress" :precision="1">
          <template #suffix><span>%</span></template>
          <template #prefix><el-icon><TrendCharts /></el-icon></template>
        </el-statistic>
      </el-card>
    </div>

    <el-card style="margin-top: 16px;">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span class="card-title">审核进度</span>
          <el-button @click="loadReviewStats" :loading="loading" size="small">
            <el-icon><Refresh /></el-icon>刷新
          </el-button>
        </div>
      </template>
      <el-progress :percentage="reviewProgress" :status="reviewProgress === 100 ? 'success' : undefined" :stroke-width="24" />
      <div class="progress-info">
        <span>已审核: {{ stats.reviewedTasks }} / {{ stats.totalTasks }}</span>
        <span>待审核: {{ stats.pendingTasks }}</span>
      </div>
    </el-card>

    <div class="toolbar">
      <el-button @click="loadResults" :loading="loading">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
      <el-select v-model="filterStatus" placeholder="筛选状态" clearable style="width: 150px; margin-left: 10px;" @change="loadResults">
        <el-option label="已审核" value="reviewed" />
        <el-option label="待审核" value="pending" />
      </el-select>
      <el-input 
        v-model="searchText" 
        placeholder="搜索图片名称" 
        clearable 
        style="width: 200px; margin-left: 10px;"
        @change="loadResults">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
    </div>

    <el-table :data="paginatedResults" v-loading="loading" style="width: 100%">
      <el-table-column prop="imageName" label="图片名称" width="250" />
      <el-table-column prop="taskId" label="任务ID" width="100" />
      <el-table-column label="审核状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.isReviewed ? 'success' : 'warning'" size="small">
            {{ row.isReviewed ? '已审核' : '待审核' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="annotationCount" label="标注数量" width="120" align="center">
        <template #default="{ row }">
          <div style="display: flex; align-items: center; justify-content: center; gap: 6px;">
            <el-icon :size="18" color="#409EFF"><Location /></el-icon>
            <span style="font-weight: 600; color: #409EFF;">{{ row.annotationCount }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="completedAt" label="完成时间" width="180">
        <template #default="{ row }">
          <span v-if="row.completedAt">{{ formatDate(row.completedAt) }}</span>
          <span v-else style="color: var(--gray-400);">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="annotatedBy" label="审核人" width="150">
        <template #default="{ row }">
          <div v-if="row.annotatedBy" style="display: flex; align-items: center; gap: 8px;">
            <el-avatar :size="24" style="background-color: var(--primary-color);">
              {{ row.annotatedBy.charAt(0).toUpperCase() }}
            </el-avatar>
            <span>{{ row.annotatedBy }}</span>
          </div>
          <span v-else style="color: var(--gray-400);">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="viewInLabelStudio(row)">
            <el-icon><View /></el-icon>在 Label Studio 中查看
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="filteredResults.length === 0" class="empty-state">
      <el-empty description="暂无审核数据" />
    </div>

    <el-pagination 
      v-if="totalPages > 1" 
      v-model:current-page="currentPage" 
      v-model:page-size="pageSize" 
      :page-sizes="[20, 50, 100]" 
      :total="filteredResults.length" 
      layout="total, sizes, prev, pager, next, jumper" 
      style="margin-top: 16px; display: flex; justify-content: flex-end;" 
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { projectAPI, labelStudioAPI } from '@/api'
import { ElMessage } from 'element-plus'
import { Document, CircleCheck, Clock, TrendCharts, Refresh, Search, Location, View } from '@element-plus/icons-vue'

const props = defineProps({ 
  project: { type: Object, required: true } 
})

const loading = ref(false)
const results = ref([])
const stats = ref({
  totalTasks: 0,
  reviewedTasks: 0,
  pendingTasks: 0
})
const filterStatus = ref('')
const searchText = ref('')
const currentPage = ref(1)
const pageSize = ref(20)

const reviewProgress = computed(() => {
  if (stats.value.totalTasks === 0) return 0
  return Math.round((stats.value.reviewedTasks / stats.value.totalTasks) * 100)
})

const filteredResults = computed(() => {
  let filtered = results.value
  
  if (filterStatus.value === 'reviewed') {
    filtered = filtered.filter(r => r.isReviewed)
  } else if (filterStatus.value === 'pending') {
    filtered = filtered.filter(r => !r.isReviewed)
  }
  
  if (searchText.value) {
    const search = searchText.value.toLowerCase()
    filtered = filtered.filter(r => r.imageName.toLowerCase().includes(search))
  }
  
  return filtered
})

const paginatedResults = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return filteredResults.value.slice(start, end)
})

const totalPages = computed(() => {
  return Math.ceil(filteredResults.value.length / pageSize.value)
})

const loadReviewStats = async () => {
  if (!props.project || !props.project.id || !props.project.labelStudioProjectId) {
    return
  }
  
  try {
    loading.value = true
    const response = await projectAPI.getReviewStats(props.project.id)
    stats.value = response.data
  } catch (error) {
    ElMessage.error('加载审核统计失败：' + (error.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

const loadResults = async () => {
  if (!props.project || !props.project.id || !props.project.labelStudioProjectId) {
    ElMessage.warning('项目尚未同步到 Label Studio')
    return
  }
  
  try {
    loading.value = true
    const response = await projectAPI.getReviewResults(props.project.id)
    results.value = response.data.tasks || []
    
    // 计算统计数据
    stats.value.totalTasks = results.value.length
    stats.value.reviewedTasks = results.value.filter(r => r.isReviewed).length
    stats.value.pendingTasks = stats.value.totalTasks - stats.value.reviewedTasks
  } catch (error) {
    ElMessage.error('加载审核结果失败：' + (error.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

const viewInLabelStudio = async (row) => {
  try {
    const response = await labelStudioAPI.getLoginUrl({
      projectId: props.project.id,
      returnUrl: `/projects/${props.project.labelStudioProjectId}/data?tab=${row.taskId}&task=${row.taskId}`
    })
    window.open(response.data, '_blank')
  } catch (error) {
    ElMessage.error('打开 Label Studio 失败')
  }
}

const formatDate = (dateString) => {
  if (!dateString) return '-'
  const date = new Date(dateString)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

onMounted(() => {
  loadResults()
  loadReviewStats()
})
</script>

<style scoped>
.review-results { }

.stats-overview {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  background: var(--gray-50) !important;
  border: none !important;
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

.toolbar {
  margin: 16px 0;
  display: flex;
  align-items: center;
}

.empty-state {
  padding: 48px 0;
}
</style>
