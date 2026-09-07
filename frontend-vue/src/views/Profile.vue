<template>
  <div class="profile-container">
    <PageHeading eyebrow="ACCOUNT CENTER" title="个人中心" description="查看账户、组织、标注服务同步状态和数据资产统计。" />

    <el-card class="profile-card">
      <div class="basic-org-info">
        <div class="avatar-section">
          <el-avatar :size="72" :src="userInfo.avatarUrl || userStore.avatarUrl" class="profile-avatar">
            {{ userInfo.username?.charAt(0)?.toUpperCase() || 'U' }}
          </el-avatar>
        </div>
        <div class="info-section">
          <div class="user-names">
            <div class="display-name">{{ userInfo.displayName || '未设置' }}</div>
            <div class="username-text">@{{ userInfo.username || 'unknown' }}</div>
          </div>
          <div class="info-list">
            <div class="info-item">
              <span class="label">邮箱</span>
              <span class="value">{{ userInfo.email || '暂无' }}</span>
            </div>
            <div class="info-item">
              <span class="label">注册时间</span>
              <span class="value">{{ formatDate(userInfo.createdAt) }}</span>
            </div>
            <div class="info-item">
              <span class="label">最后登录</span>
              <span class="value">{{ formatDate(userInfo.lastLoginAt) }}</span>
            </div>
            <div class="info-divider"></div>
            <div class="info-item">
              <span class="label">组织名称</span>
              <span class="value">{{ userInfo.orgDisplayName || userInfo.orgName || '暂无' }}</span>
            </div>
            <div class="info-item">
              <span class="label">身份标识</span>
              <el-tag v-if="userInfo.isOrgCreator" type="success" size="small">组织创建者</el-tag>
              <el-tag v-else type="info" size="small">普通成员</el-tag>
            </div>
          </div>
        </div>
      </div>
    </el-card>

    <el-card class="profile-card">
      <template #header>
        <span class="card-title">系统状态</span>
      </template>
      <div class="info-list">
        <div class="info-item">
          <span class="label">账户状态</span>
          <el-tag v-if="userInfo.isActive" type="success" size="small">正常</el-tag>
          <el-tag v-else type="danger" size="small">禁用</el-tag>
        </div>
        <div class="info-item">
          <span class="label">LS 同步状态</span>
          <el-tag v-if="userInfo.lsSyncStatus" type="success" size="small">已同步</el-tag>
          <el-tag v-else type="warning" size="small">未同步</el-tag>
        </div>
        <div class="info-item">
          <span class="label">LS 用户 ID</span>
          <span class="value">{{ userInfo.lsUserId || '暂无' }}</span>
        </div>
      </div>
      <div class="actions-row">
        <el-button type="primary" :loading="labelStudioOpening" :disabled="labelStudioOpening" @click="jumpToLabelStudio" style="flex: 1;">
          <el-icon style="margin-right: 6px;"><Monitor /></el-icon>
          进入智能标注
        </el-button>
        <el-button @click="router.push('/settings')" style="flex: 1;">
          <el-icon style="margin-right: 6px;"><Edit /></el-icon>
          编辑资料
        </el-button>
      </div>
    </el-card>

    <el-card class="profile-card">
      <template #header>
        <span class="card-title">数据统计</span>
      </template>
      <div class="stat-grid">
        <div class="stat-box">
          <div class="stat-num">{{ userInfo.totalProjects || 0 }}</div>
          <div class="stat-label">总项目数</div>
        </div>
        <div class="stat-box">
          <div class="stat-num">{{ userInfo.totalImages || 0 }}</div>
          <div class="stat-label">总图片数</div>
        </div>
        <div class="stat-box">
          <div class="stat-num">{{ userInfo.processingTasks || 0 }}</div>
          <div class="stat-label">进行中任务</div>
        </div>
        <div class="stat-box">
          <div class="stat-num">{{ userInfo.completedTasks || 0 }}</div>
          <div class="stat-label">已完成任务</div>
        </div>
      </div>
    </el-card>

    <el-card class="profile-card" v-if="userInfo.recentProjects && userInfo.recentProjects.length > 0">
      <template #header>
        <span class="card-title">最近项目</span>
      </template>
      <el-table :data="userInfo.recentProjects" stripe style="width: 100%">
        <el-table-column prop="name" label="项目名称" min-width="180" />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalImages" label="总图片" width="100" align="right" />
        <el-table-column prop="processedImages" label="已处理" width="100" align="right" />
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="router.push(`/projects/${row.id}`)">
              查看
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'
import { Monitor, Edit } from '@element-plus/icons-vue'
import { labelStudioAPI } from '@/api'
import {
  closeLabelStudioPendingWindow,
  openLabelStudioPendingWindow,
  openLabelStudioUrl
} from '@/utils/labelStudioUrl'
import PageHeading from '@/components/platform-ui/PageHeading.vue'

const router = useRouter()
const userStore = useUserStore()
const labelStudioOpening = ref(false)

const userInfo = computed(() => userStore.userInfo || {})

onMounted(async () => {
  try {
    await userStore.fetchUserProfile()
  } catch (error) {
    console.error('加载用户资料失败:', error)
    ElMessage.error('加载用户资料失败，请刷新页面重试')
  }
})

const formatDate = (dateString) => {
  if (!dateString) return '暂无'
  const date = new Date(dateString)
  return date.toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  })
}

const getStatusType = (status) => {
  const statusMap = {
    'DRAFT': 'info', 'UPLOADING': 'warning', 'DETECTING': 'primary',
    'CLEANING': 'primary', 'SYNCING': 'primary', 'COMPLETED': 'success', 'FAILED': 'danger'
  }
  return statusMap[status] || 'info'
}

const getDefaultProjectId = () => {
  const recentProjects = userInfo.value.recentProjects || []
  return recentProjects.find(project => project?.id)?.id || null
}

const jumpToLabelStudio = async () => {
  if (labelStudioOpening.value) return

  const pendingWindow = openLabelStudioPendingWindow()
  labelStudioOpening.value = true
  try {
    const projectId = getDefaultProjectId()
    const response = await labelStudioAPI.getLoginUrl(projectId ? { projectId } : undefined)
    if (response.data) {
      openLabelStudioUrl(response.data, pendingWindow)
    } else {
      closeLabelStudioPendingWindow(pendingWindow)
      ElMessage.error('未能获取星目智能标注入口')
    }
  } catch (error) {
    closeLabelStudioPendingWindow(pendingWindow)
    console.error('跳转到星目智能标注失败:', error)
    ElMessage.error('跳转失败，请稍后重试')
  } finally {
    labelStudioOpening.value = false
  }
}
</script>

<style scoped>
.profile-container {
  max-width: 1000px;
}

.profile-card {
  margin-bottom: 16px;
}

.card-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--gray-900);
}

.basic-org-info {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}

.profile-avatar {
  background-color: var(--brand-50) !important;
  color: var(--brand-800) !important;
  font-weight: 600;
  font-size: 24px;
}

.user-names {
  margin-bottom: 20px;
}

.display-name {
  font-size: 20px;
  font-weight: 600;
  color: var(--gray-900);
  letter-spacing: -0.02em;
}

.username-text {
  font-size: 13px;
  color: var(--gray-400);
  margin-top: 2px;
}

.info-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
}

.info-item .label {
  font-size: 13px;
  color: var(--gray-500);
}

.info-item .value {
  font-size: 13px;
  color: var(--gray-900);
  font-weight: 500;
}

.info-divider {
  height: 0.5px;
  background: var(--gray-200);
  margin: 4px 0;
}

.actions-row {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.stat-box {
  background: var(--gray-50);
  border-radius: var(--radius-md);
  padding: 16px;
  text-align: center;
}

.stat-num {
  font-size: 24px;
  font-weight: 600;
  color: var(--gray-900);
  letter-spacing: -0.02em;
}

.stat-label {
  font-size: 12px;
  color: var(--gray-500);
  margin-top: 4px;
  font-weight: 500;
}

@media (max-width: 768px) {
  .basic-org-info {
    flex-direction: column;
    align-items: center;
  }
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
