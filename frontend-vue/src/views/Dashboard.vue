<template>
  <div class="dashboard">
    <div class="page-title">概览</div>

    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-icon" style="background: var(--brand-50); color: var(--brand-600);">
          <el-icon><FolderOpened /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.totalProjects }}</div>
          <div class="stat-label">总项目数</div>
        </div>
      </div>
      
      <div class="stat-card">
        <div class="stat-icon" style="background: var(--success-bg); color: var(--success-text);">
          <el-icon><Picture /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.totalImages }}</div>
          <div class="stat-label">总图片数</div>
        </div>
      </div>
      
      <div class="stat-card">
        <div class="stat-icon" style="background: var(--warning-bg); color: var(--warning-text);">
          <el-icon><Timer /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.runningTasks }}</div>
          <div class="stat-label">运行中任务</div>
        </div>
      </div>
      
      <div class="stat-card">
        <div class="stat-icon" style="background: var(--danger-bg); color: var(--danger-text);">
          <el-icon><CircleCheck /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-value">{{ stats.completedTasks }}</div>
          <div class="stat-label">已完成任务</div>
        </div>
      </div>
    </div>
    
    <el-card class="table-card">
      <template #header>
        <div class="card-header">
          <span>最近项目</span>
          <el-button type="primary" link @click="goToProjects">查看全部</el-button>
        </div>
      </template>
      <el-table :data="recentProjects" style="width: 100%">
        <el-table-column prop="name" label="项目名称" />
        <el-table-column prop="status" label="状态">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalImages" label="图片数" />
        <el-table-column prop="processedImages" label="已处理" />
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="viewProject(row.id)">
              查看
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { projectAPI, userAPI } from '@/api'
import { getProjectStatusText, getProjectStatusType } from '@/utils/projectStatus'

const router = useRouter()

const stats = ref({
  totalProjects: 0,
  totalImages: 0,
  runningTasks: 0,
  completedTasks: 0
})

const recentProjects = ref([])

const getStatusType = (status) => {
  return getProjectStatusType(status)
}

const getStatusText = (status) => {
  return getProjectStatusText(status)
}

const goToProjects = () => {
  router.push('/projects')
}

const viewProject = (id) => {
  router.push(`/projects/${id}`)
}

const loadStats = async () => {
  try {
    const response = await userAPI.getOrganizationStats()
    if (response.data) {
      stats.value = response.data
    }
  } catch (error) {
    console.error('加载统计数据失败:', error)
  }
}

const loadRecentProjects = async () => {
  try {
    const response = await projectAPI.getProjects({ page: 0, size: 5 })
    const data = response.data || {}
    const projects = Array.isArray(data) ? data : data.content || []
    recentProjects.value = projects.map(project => ({
      id: project.id,
      name: project.name,
      status: project.status,
      totalImages: project.totalImages || 0,
      processedImages: project.processedImages || 0,
      createdAt: project.createdAt
    }))
  } catch (error) {
    console.error('加载最近项目失败:', error)
  }
}

const formatDate = (dateString) => {
  if (!dateString) return '-'
  const date = new Date(dateString)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

onMounted(() => {
  loadStats()
  loadRecentProjects()
})
</script>

<style scoped>
.dashboard {
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--gray-900);
  letter-spacing: -0.02em;
  margin-bottom: 24px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  background: #fff;
  border: 0.5px solid var(--gray-200);
  border-radius: var(--radius-lg);
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  transition: border-color 0.15s;
}

.stat-card:hover {
  border-color: var(--gray-300);
}

.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
}

.stat-body {
  flex: 1;
  min-width: 0;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: var(--gray-900);
  line-height: 1.2;
  letter-spacing: -0.02em;
}

.stat-label {
  font-size: 12px;
  color: var(--gray-500);
  margin-top: 2px;
  font-weight: 500;
}

.table-card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 15px;
  font-weight: 500;
  color: var(--gray-900);
}
</style>
