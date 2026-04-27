<template>
  <div class="project-detail">
    <el-page-header @back="goBack" :content="project.name">
      <template #extra>
        <el-button 
          type="primary" 
          @click="openLabelStudio"
          :disabled="isProcessing"
          :loading="isProcessing">
          <el-icon><Link /></el-icon>
          {{ buttonText }}
        </el-button>
      </template>
    </el-page-header>

    <el-alert
      v-if="userProfile.lsEmail && userProfile.lsPassword"
      type="info"
      :closable="false"
      style="margin-top: 16px;">
      <template #title>
        <div style="display: flex; align-items: center; gap: 8px;">
          <el-icon><Key /></el-icon>
          <span style="font-weight: 500;">Label Studio 登录凭证</span>
        </div>
      </template>
      <div style="display: flex; gap: 24px; margin-top: 8px;">
        <div style="display: flex; align-items: center; gap: 8px;">
          <span style="color: var(--gray-600); font-size: 13px;">邮箱:</span>
          <el-tag size="small" style="font-family: monospace;">{{ userProfile.lsEmail }}</el-tag>
          <el-button size="small" text @click="copyToClipboard(userProfile.lsEmail, '邮箱')">
            <el-icon><DocumentCopy /></el-icon>
          </el-button>
        </div>
        <div style="display: flex; align-items: center; gap: 8px;">
          <span style="color: var(--gray-600); font-size: 13px;">密码:</span>
          <el-tag size="small" style="font-family: monospace;">{{ userProfile.lsPassword }}</el-tag>
          <el-button size="small" text @click="copyToClipboard(userProfile.lsPassword, '密码')">
            <el-icon><DocumentCopy /></el-icon>
          </el-button>
        </div>
      </div>
    </el-alert>

    <div v-if="project.id" class="project-tabs-wrapper">
      <el-tabs v-model="activeTab" class="project-tabs">
        <el-tab-pane label="数据与标注" name="workspace">
          <div class="workspace-page">
            <el-card shadow="never" class="workspace-card labels-card">
              <template #header><span class="card-title">类别定义</span></template>
              <LabelDefinition :project="project" @refresh="loadProject" />
            </el-card>

            <el-card shadow="never" class="workspace-card data-card">
              <template #header><span class="card-title">数据管理</span></template>
              <DataManager :project="project" @refresh="loadProject" />
            </el-card>

            <el-card shadow="never" class="workspace-card annotation-card">
              <template #header><span class="card-title">自动标注</span></template>
              <AlgorithmTasks :project="project" @refresh="loadProject" />
            </el-card>
          </div>
        </el-tab-pane>

        <el-tab-pane label="结果查看" name="results">
          <ResultViewer :project="project" />
        </el-tab-pane>

        <el-tab-pane label="模型训练" name="training">
          <Training :project="project" @refresh="loadProject" />
        </el-tab-pane>

        <el-tab-pane label="边端模拟" name="edge">
          <EdgeSimulator :project="project" @refresh="loadProject" />
        </el-tab-pane>

      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { projectAPI, labelStudioAPI, userAPI } from '@/api'
import { ElMessage } from 'element-plus'
import { Key, DocumentCopy, Link } from '@element-plus/icons-vue'
import LabelDefinition from '@/components/LabelDefinition.vue'
import DataManager from '@/components/DataManager.vue'
import AlgorithmTasks from '@/components/AlgorithmTasks.vue'
import ResultViewer from '@/components/ResultViewer.vue'
import Training from '@/components/Training.vue'
import EdgeSimulator from '@/components/EdgeSimulator.vue'

const router = useRouter()
const route = useRoute()

const activeTab = ref('workspace')
const project = ref({
  id: null,
  name: '',
  status: 'DRAFT',
  totalImages: 0,
  processedImages: 0,
  labels: [],
  createdAt: '',
  updatedAt: '',
  labelStudioProjectId: null
})

const userProfile = ref({
  lsEmail: '',
  lsPassword: ''
})

const isProcessing = computed(() => {
  return ['DETECTING', 'CLEANING', 'SYNCING', 'UPLOADING'].includes(project.value.status)
})

let pollInterval = null

// 当项目处于处理状态时，定时刷新项目数据
watch(() => project.value?.status, (status) => {
  const processingStatuses = ['DETECTING', 'CLEANING', 'SYNCING', 'UPLOADING']
  
  if (processingStatuses.includes(status) && !pollInterval) {
    // 启动轮询
    pollInterval = setInterval(() => {
      loadProject()
    }, 3000)
  } else if (!processingStatuses.includes(status) && pollInterval) {
    // 停止轮询
    clearInterval(pollInterval)
    pollInterval = null
  }
}, { immediate: true })

const buttonText = computed(() => {
  if (isProcessing.value) {
    const statusMap = {
      'DETECTING': '检测中...',
      'CLEANING': '清洗中...',
      'SYNCING': '同步中...'
    }
    return statusMap[project.value.status] || '处理中...'
  }
  return '打开 Label Studio'
})

const loadProject = async () => {
  try {
    const projectId = route.params.id
    if (!projectId || projectId === 'null') {
      ElMessage.error('无效的项目ID')
      router.push('/projects')
      return
    }
    const response = await projectAPI.getProjectById(Number(projectId))
    project.value = response.data
  } catch (error) {
    ElMessage.error('加载项目信息失败')
  }
}

const loadUserProfile = async () => {
  try {
    const response = await userAPI.getUserProfile()
    if (response.data) {
      userProfile.value.lsEmail = response.data.lsEmail || ''
      userProfile.value.lsPassword = response.data.lsPassword || ''
    }
  } catch (error) {
    console.error('加载用户信息失败:', error)
  }
}

const copyToClipboard = async (text, label) => {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.style.position = 'fixed'
      textarea.style.left = '-9999px'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    ElMessage.success(`${label}已复制到剪贴板`)
  } catch (error) {
    ElMessage.error('复制失败，请手动复制')
  }
}

const openLabelStudio = async () => {
  try {
    const response = await labelStudioAPI.getLoginUrl({
      projectId: project.value.id
    })
    const loginUrl = normalizeLabelStudioUrl(response.data)
    if (loginUrl) {
      window.open(loginUrl, '_blank')
    } else {
      ElMessage.error('未能获取 Label Studio 登录链接')
    }
  } catch (error) {
    ElMessage.error('打开 Label Studio 失败')
  }
}

const normalizeLabelStudioUrl = (url) => {
  if (!url) return url
  try {
    const parsed = new URL(url)
    if (parsed.hostname === 'localhost' && window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
      parsed.hostname = window.location.hostname
    }
    return parsed.toString()
  } catch (error) {
    return url
  }
}

const goBack = () => {
  router.push('/projects')
}

onMounted(() => {
  loadProject()
  loadUserProfile()
})

onUnmounted(() => {
  if (pollInterval) {
    clearInterval(pollInterval)
    pollInterval = null
  }
})
</script>

<style scoped>
.project-detail {
}

.project-tabs-wrapper {
  margin-top: 20px;
  background: #fff;
  border: 0.5px solid var(--gray-200);
  border-radius: var(--radius-lg);
  padding: 14px 18px 18px;
}

.workspace-page {
  display: grid;
  grid-template-columns: minmax(300px, 0.9fr) minmax(420px, 1.5fr);
  gap: 14px;
  align-items: start;
}

.workspace-card {
  border-radius: 8px;
}

.workspace-card :deep(.el-card__header) {
  padding: 10px 14px;
}

.workspace-card :deep(.el-card__body) {
  padding: 14px;
}

.data-card {
  grid-row: span 2;
}

.annotation-card {
  grid-column: 1 / -1;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--gray-900);
}

@media (max-width: 1180px) {
  .workspace-page {
    grid-template-columns: 1fr;
  }

  .data-card,
  .annotation-card {
    grid-column: auto;
    grid-row: auto;
  }
}
</style>
