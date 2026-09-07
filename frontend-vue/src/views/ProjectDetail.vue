<template>
  <div class="project-detail">
    <div class="detail-hero">
      <button class="back-link" type="button" @click="goBack">← 返回项目</button>
      <PageHeading eyebrow="PROJECT WORKSPACE" :title="project.name || '项目详情'" description="在同一工作区完成智能标注、训练测试、边端下发和模型迭代。">
        <div class="detail-metrics" aria-label="项目数据概览">
          <span>数据量 <strong>{{ project.totalImages || 0 }}</strong></span>
          <span>已处理 <strong>{{ project.processedImages || 0 }}</strong></span>
        </div>
      </PageHeading>
    </div>

    <el-alert
      v-if="userProfile.lsEmail"
      type="info"
      :closable="false"
      style="margin-top: 16px;">
      <template #title>
        <div style="display: flex; align-items: center; gap: 8px;">
          <el-icon><Link /></el-icon>
          <span style="font-weight: 500;">星目智能标注已同步</span>
        </div>
      </template>
      <div style="display: flex; gap: 24px; margin-top: 8px;">
        <div style="display: flex; align-items: center; gap: 8px;">
          <span style="color: var(--gray-600); font-size: 13px;">邮箱:</span>
          <el-tag size="small" style="font-family: monospace;">{{ maskEmail(userProfile.lsEmail) }}</el-tag>
          <el-button size="small" text @click="copyToClipboard(userProfile.lsEmail, '邮箱')">
            <el-icon><DocumentCopy /></el-icon>
          </el-button>
        </div>
      </div>
    </el-alert>

    <div v-if="project.id" class="project-tabs-wrapper">
      <el-tabs v-model="activeTab" class="project-tabs">
        <el-tab-pane label="智能标注训练" name="autoLabel">
          <ProjectAutoLabel embedded />
        </el-tab-pane>

        <el-tab-pane label="训练与测试" name="training">
          <Training :project="project" @refresh="loadProject" />
        </el-tab-pane>

        <el-tab-pane label="边端下发" name="edge">
          <EdgeSimulator :project="project" @refresh="loadProject" />
        </el-tab-pane>

      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { projectAPI, userAPI } from '@/api'
import { ElMessage } from 'element-plus'
import { DocumentCopy, Link } from '@element-plus/icons-vue'
import { isProjectProcessing } from '@/utils/projectStatus'
import Training from '@/components/Training.vue'
import EdgeSimulator from '@/components/EdgeSimulator.vue'
import ProjectAutoLabel from '@/views/ProjectAutoLabel.vue'
import PageHeading from '@/components/platform-ui/PageHeading.vue'

const router = useRouter()
const route = useRoute()

const activeTab = ref('autoLabel')
const project = ref({
  id: null,
  name: '',
  status: 'DRAFT',
  totalImages: 0,
  processedImages: 0,
  labels: [],
  createdAt: '',
  updatedAt: '',
  lsProjectId: null
})

const userProfile = ref({
  lsEmail: ''
})

let pollInterval = null

// 当项目处于处理状态时，定时刷新项目数据
watch(() => project.value?.status, (status) => {
  if (isProjectProcessing(status) && !pollInterval) {
    // 启动轮询
    pollInterval = setInterval(() => {
      loadProject()
    }, 3000)
  } else if (!isProjectProcessing(status) && pollInterval) {
    // 停止轮询
    clearInterval(pollInterval)
    pollInterval = null
  }
}, { immediate: true })

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

const maskEmail = (email) => {
  if (!email) return ''
  const [name, domain] = email.split('@')
  if (!domain) return email
  return `${name.slice(0, 2)}***@${domain}`
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

.detail-hero {
  margin-bottom: 18px;
}

.back-link {
  margin: 0 0 16px;
  padding: 0;
  color: var(--brand-700);
  font-size: 13px;
  font-weight: 700;
  background: none;
  border: 0;
  cursor: pointer;
}

.back-link:hover { color: var(--brand-600); }

.detail-metrics {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}

.detail-metrics span {
  min-width: 108px;
  padding: 10px 13px;
  color: var(--gray-500);
  font-size: 12px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
}

.detail-metrics strong {
  display: block;
  margin-top: 2px;
  color: var(--ink-950);
  font-size: 19px;
}

.project-tabs-wrapper {
  margin-top: 20px;
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-lg);
  padding: 14px 18px 18px;
}

@media (max-width: 760px) {
  .detail-metrics { width: 100%; }
  .detail-metrics span { flex: 1; }
}

</style>
