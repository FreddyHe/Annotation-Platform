<template>
  <div class="dashboard">
    <el-row :gutter="20">
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <el-icon class="stat-icon" color="#409eff"><FolderOpened /></el-icon>
            <div class="stat-info">
              <div class="stat-value">{{ stats.totalProjects }}</div>
              <div class="stat-label">总项目数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <el-icon class="stat-icon" color="#67c23a"><Picture /></el-icon>
            <div class="stat-info">
              <div class="stat-value">{{ stats.totalImages }}</div>
              <div class="stat-label">总图片数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <el-icon class="stat-icon" color="#e6a23c"><Timer /></el-icon>
            <div class="stat-info">
              <div class="stat-value">{{ stats.runningTasks }}</div>
              <div class="stat-label">运行中任务</div>
            </div>
          </div>
        </el-card>
      </el-col>
      
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <el-icon class="stat-icon" color="#f56c6c"><CircleCheck /></el-icon>
            <div class="stat-info">
              <div class="stat-value">{{ stats.completedTasks }}</div>
              <div class="stat-label">已完成任务</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
    
    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col :span="24">
        <el-card>
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
                <el-tag :type="getStatusType(row.status)">
                  {{ getStatusText(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="totalImages" label="图片数" />
            <el-table-column prop="processedImages" label="已处理" />
            <el-table-column prop="createdAt" label="创建时间" />
            <el-table-column label="操作" width="200">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="viewProject(row.id)">
                  查看
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { userAPI } from '@/api'

const router = useRouter()

const stats = ref({
  totalProjects: 0,
  totalImages: 0,
  runningTasks: 0,
  completedTasks: 0
})

const recentProjects = ref([])

const getStatusType = (status) => {
  const typeMap = {
    'DRAFT': 'info',
    'UPLOADING': 'warning',
    'PROCESSING': 'primary',
    'COMPLETED': 'success',
    'FAILED': 'danger'
  }
  return typeMap[status] || 'info'
}

const getStatusText = (status) => {
  const textMap = {
    'DRAFT': '草稿',
    'UPLOADING': '上传中',
    'PROCESSING': '处理中',
    'COMPLETED': '已完成',
    'FAILED': '失败'
  }
  return textMap[status] || status
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
    const response = await fetch('/api/v1/projects?page=0&size=3&status=PROCESSING', {
      headers: {
        'Authorization': `Bearer ${localStorage.getItem('token')}`
      }
    })
    if (response.ok) {
      const data = await response.json()
      if (data.data && data.data.length > 0) {
        recentProjects.value = data.data.map(project => ({
          id: project.id,
          name: project.name,
          status: project.status,
          totalImages: project.totalImages || 0,
          processedImages: project.processedImages || 0,
          createdAt: project.createdAt
        }))
      } else {
        const response2 = await fetch('/api/v1/projects?page=0&size=3', {
          headers: {
            'Authorization': `Bearer ${localStorage.getItem('token')}`
          }
        })
        if (response2.ok) {
          const data2 = await response2.json()
          if (data2.data && data2.data.length > 0) {
            recentProjects.value = data2.data.map(project => ({
              id: project.id,
              name: project.name,
              status: project.status,
              totalImages: project.totalImages || 0,
              processedImages: project.processedImages || 0,
              createdAt: project.createdAt
            }))
          }
        }
      }
    }
  } catch (error) {
    console.error('加载最近项目失败:', error)
  }
}

onMounted(() => {
  loadStats()
  loadRecentProjects()
})
</script>

<style scoped>
.dashboard {
  padding: 20px;
}

.stat-card {
  margin-bottom: 20px;
}

.stat-content {
  display: flex;
  align-items: center;
  padding: 10px;
}

.stat-icon {
  font-size: 48px;
  margin-right: 20px;
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 28px;
  font-weight: bold;
  color: #303133;
  margin-bottom: 5px;
}

.stat-label {
  font-size: 14px;
  color: #909399;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
