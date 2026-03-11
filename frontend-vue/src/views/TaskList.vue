<template>
  <div class="task-list">
    <el-card>
      <template #header>
        <span>算法任务</span>
      </template>
      
      <el-table :data="tasks" v-loading="loading" style="width: 100%">
        <el-table-column prop="taskId" label="任务ID" />
        <el-table-column prop="taskType" label="任务类型">
          <template #default="{ row }">
            <el-tag :type="getTaskTypeColor(row.taskType)">
              {{ getTaskTypeText(row.taskType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="progress" label="进度">
          <template #default="{ row }">
            <el-progress :percentage="row.progress" :status="getProgressStatus(row.status)" />
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="viewTask(row.taskId)">
              查看
            </el-button>
            <el-button
              v-if="row.status === 'RUNNING'"
              type="danger"
              link
              size="small"
              @click="cancelTask(row.taskId)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const loading = ref(false)
const tasks = ref([])

const getTaskTypeColor = (type) => {
  const colorMap = {
    'DINO_DETECTION': 'primary',
    'VLM_CLEANING': 'warning',
    'YOLO_DETECTION': 'success'
  }
  return colorMap[type] || 'info'
}

const getTaskTypeText = (type) => {
  const textMap = {
    'DINO_DETECTION': 'DINO 检测',
    'VLM_CLEANING': 'VLM 清洗',
    'YOLO_DETECTION': 'YOLO 检测'
  }
  return textMap[type] || type
}

const getStatusType = (status) => {
  const typeMap = {
    'PENDING': 'info',
    'RUNNING': 'primary',
    'COMPLETED': 'success',
    'FAILED': 'danger',
    'CANCELLED': 'warning'
  }
  return typeMap[status] || 'info'
}

const getStatusText = (status) => {
  const textMap = {
    'PENDING': '等待中',
    'RUNNING': '运行中',
    'COMPLETED': '已完成',
    'FAILED': '失败',
    'CANCELLED': '已取消'
  }
  return textMap[status] || status
}

const getProgressStatus = (status) => {
  const statusMap = {
    'COMPLETED': 'success',
    'FAILED': 'exception',
    'CANCELLED': 'warning'
  }
  return statusMap[status] || ''
}

const viewTask = (taskId) => {
  ElMessage.info(`查看任务 ${taskId} 详情功能开发中`)
}

const cancelTask = (taskId) => {
  ElMessageBox.confirm('确定要取消该任务吗？', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    ElMessage.success('任务已取消')
  }).catch(() => {})
}

onMounted(() => {
  loading.value = true
  setTimeout(() => {
    tasks.value = [
      {
        taskId: 'task-001',
        taskType: 'DINO_DETECTION',
        status: 'RUNNING',
        progress: 45,
        createdAt: '2024-01-15 10:30:00'
      },
      {
        taskId: 'task-002',
        taskType: 'VLM_CLEANING',
        status: 'COMPLETED',
        progress: 100,
        createdAt: '2024-01-14 15:20:00'
      },
      {
        taskId: 'task-003',
        taskType: 'YOLO_DETECTION',
        status: 'FAILED',
        progress: 30,
        createdAt: '2024-01-13 09:15:00'
      }
    ]
    loading.value = false
  }, 500)
})
</script>

<style scoped>
.task-list {
  padding: 20px;
}
</style>
