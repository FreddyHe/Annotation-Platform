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

    <el-tabs v-if="project.id" v-model="activeTab" class="project-tabs" style="margin-top: 20px;">
      <el-tab-pane label="🏷️ 类别定义" name="labels">
        <LabelDefinition :project="project" @refresh="loadProject" />
      </el-tab-pane>

      <el-tab-pane label="📤 数据管理" name="data">
        <DataManager :project="project" @refresh="loadProject" />
      </el-tab-pane>

      <el-tab-pane label="🚀 自动标注" name="tasks">
        <AlgorithmTasks :project="project" @refresh="loadProject" />
      </el-tab-pane>

      <el-tab-pane label="📊 结果查看" name="results">
        <ResultViewer :project="project" />
      </el-tab-pane>

      <el-tab-pane label="💾 导出结果" name="export">
        <ResultExport :project="project" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { projectAPI, labelStudioAPI } from '@/api'
import { ElMessage } from 'element-plus'
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

const openLabelStudio = async () => {
  try {
    const response = await labelStudioAPI.getLoginUrl({
      projectId: project.value.id
    })
    window.open(response.data.loginUrl, '_blank')
  } catch (error) {
    ElMessage.error('打开 Label Studio 失败')
  }
}

const goBack = () => {
  router.push('/projects')
}

onMounted(() => {
  loadProject()
})
</script>

<style scoped>
.project-detail {
  padding: 20px;
}

.project-tabs {
  background: #fff;
  padding: 20px;
  border-radius: 8px;
}
</style>
