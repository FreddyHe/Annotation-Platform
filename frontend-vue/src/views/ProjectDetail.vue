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
        <el-tab-pane label="类别定义" name="labels">
          <LabelDefinition :project="project" @refresh="loadProject" />
        </el-tab-pane>

        <el-tab-pane label="数据管理" name="data">
          <DataManager :project="project" @refresh="loadProject" />
        </el-tab-pane>

        <el-tab-pane label="自动标注" name="tasks">
          <AlgorithmTasks :project="project" @refresh="loadProject" />
        </el-tab-pane>

        <el-tab-pane label="结果查看" name="results">
          <ResultViewer :project="project" />
        </el-tab-pane>

        <el-tab-pane label="导出结果" name="export">
          <ResultExport :project="project" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { projectAPI, labelStudioAPI, userAPI } from '@/api'
import { ElMessage } from 'element-plus'
import { Key, DocumentCopy } from '@element-plus/icons-vue'
import LabelDefinition from '@/components/LabelDefinition.vue'
import DataManager from '@/components/DataManager.vue'
import AlgorithmTasks from '@/components/AlgorithmTasks.vue'
import ResultViewer from '@/components/ResultViewer.vue'
import ResultExport from '@/components/ResultExport.vue'

const router = useRouter()
const route = useRoute()

const activeTab = ref('labels')
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
  return ['DETECTING', 'CLEANING', 'SYNCING'].includes(project.value.status)
})

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
    await navigator.clipboard.writeText(text)
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
    const loginUrl = response.data
    if (loginUrl) {
      window.open(loginUrl, '_blank')
    } else {
      ElMessage.error('未能获取 Label Studio 登录链接')
    }
  } catch (error) {
    ElMessage.error('打开 Label Studio 失败')
  }
}

const goBack = () => {
  router.push('/projects')
}

onMounted(() => {
  loadProject()
  loadUserProfile()
})
</script>

<style scoped>
.project-detail {
  max-width: 1200px;
}

.project-tabs-wrapper {
  margin-top: 20px;
  background: #fff;
  border: 0.5px solid var(--gray-200);
  border-radius: var(--radius-lg);
  padding: 20px 24px;
}
</style>
