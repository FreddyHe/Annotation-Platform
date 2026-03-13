<template>
  <div class="profile-container">
    <el-card class="profile-card">
      <template #header>
        <div class="card-header">
          <span>基础信息与组织</span>
        </div>
      </template>
      <div class="basic-org-info">
        <div class="avatar-section">
          <el-avatar :size="80" :src="userInfo.avatarUrl || userStore.avatarUrl">
            {{ userInfo.username?.charAt(0)?.toUpperCase() || 'U' }}
          </el-avatar>
        </div>
        <div class="info-section">
          <div class="user-names">
            <div class="display-name">{{ userInfo.displayName || '未设置' }}</div>
            <div class="username">@{{ userInfo.username || 'unknown' }}</div>
          </div>
          <el-divider />
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
            <el-divider />
            <div class="info-item">
              <span class="label">组织名称</span>
              <span class="value">{{ userInfo.orgDisplayName || userInfo.orgName || '暂无' }}</span>
            </div>
            <div class="info-item">
              <span class="label">组织 ID</span>
              <span class="value">{{ userInfo.orgId || '暂无' }}</span>
            </div>
            <div class="info-item">
              <span class="label">身份标识</span>
              <el-tag v-if="userInfo.isOrgCreator" type="success" effect="dark">组织创建者</el-tag>
              <el-tag v-else type="info" effect="dark">普通成员</el-tag>
            </div>
          </div>
        </div>
      </div>
    </el-card>

    <el-card class="profile-card">
      <template #header>
        <div class="card-header">
          <span>系统状态与快捷操作</span>
        </div>
      </template>
      <div class="system-actions">
        <div class="system-info">
          <div class="info-item">
            <span class="label">账户状态</span>
            <el-tag v-if="userInfo.isActive" type="success" effect="dark">正常</el-tag>
            <el-tag v-else type="danger" effect="dark">禁用</el-tag>
          </div>
          <div class="info-item">
            <span class="label">LS 同步状态</span>
            <el-tag v-if="userInfo.lsSyncStatus" type="success" effect="dark">已同步</el-tag>
            <el-tag v-else type="warning" effect="dark">未同步</el-tag>
          </div>
          <div class="info-item">
            <span class="label">LS 用户 ID</span>
            <span class="value">{{ userInfo.lsUserId || '暂无' }}</span>
          </div>
        </div>
        <el-divider />
        <div class="actions">
          <el-button type="primary" size="large" @click="jumpToLabelStudio" style="width: 100%; margin-bottom: 15px">
            <el-icon style="margin-right: 8px"><Monitor /></el-icon>
            进入 Label Studio 工作台
          </el-button>
          <el-button size="large" @click="router.push('/settings')" style="width: 100%">
            <el-icon style="margin-right: 8px"><Edit /></el-icon>
            编辑资料
          </el-button>
        </div>
      </div>
    </el-card>

    <el-card class="profile-card">
      <template #header>
        <div class="card-header">
          <span>数据统计</span>
        </div>
      </template>
      <div class="statistics-row">
        <div class="stat-item">
          <el-statistic title="总项目数" :value="userInfo.totalProjects || 0">
            <template #suffix>
              <span style="font-size: 14px">个</span>
            </template>
          </el-statistic>
        </div>
        <div class="stat-item">
          <el-statistic title="总图片数" :value="userInfo.totalImages || 0">
            <template #suffix>
              <span style="font-size: 14px">张</span>
            </template>
          </el-statistic>
        </div>
        <div class="stat-item">
          <el-statistic title="进行中任务" :value="userInfo.processingTasks || 0">
            <template #suffix>
              <span style="font-size: 14px">个</span>
            </template>
          </el-statistic>
        </div>
        <div class="stat-item">
          <el-statistic title="已完成任务" :value="userInfo.completedTasks || 0">
            <template #suffix>
              <span style="font-size: 14px">个</span>
            </template>
          </el-statistic>
        </div>
      </div>
    </el-card>

    <el-card class="profile-card" v-if="userInfo.recentProjects && userInfo.recentProjects.length > 0">
      <template #header>
        <div class="card-header">
          <span>最近项目</span>
        </div>
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

const router = useRouter()
const userStore = useUserStore()

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
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

const getStatusType = (status) => {
  const statusMap = {
    'DRAFT': 'info',
    'UPLOADING': 'warning',
    'DETECTING': 'primary',
    'CLEANING': 'primary',
    'SYNCING': 'primary',
    'COMPLETED': 'success',
    'FAILED': 'danger'
  }
  return statusMap[status] || 'info'
}

const jumpToLabelStudio = () => {
  if (!userInfo.value.lsEmail || !userInfo.value.lsPassword) {
    ElMessage.warning('未能获取到 Label Studio 登录凭证，请联系管理员')
    return
  }

  const lsLoginUrl = 'http://122.51.47.91:28450/user/login/'
  
  const form = document.createElement('form')
  form.action = lsLoginUrl
  form.method = 'POST'
  form.target = '_blank'
  form.style.display = 'none'

  const emailInput = document.createElement('input')
  emailInput.name = 'email'
  emailInput.value = userInfo.value.lsEmail
  
  const passwordInput = document.createElement('input')
  passwordInput.name = 'password'
  passwordInput.value = userInfo.value.lsPassword

  form.appendChild(emailInput)
  form.appendChild(passwordInput)
  document.body.appendChild(form)
  
  form.submit()
  
  setTimeout(() => {
    document.body.removeChild(form)
  }, 100)
}
</script>

<style scoped>
.profile-container {
  padding: 20px;
  max-width: 1200px;
  margin: 0 auto;
  background-color: #f5f7fa;
  min-height: calc(100vh - 60px);
}

.card-header {
  font-size: 18px;
  font-weight: bold;
  color: #303133;
}

.profile-card {
  margin-bottom: 20px;
  width: 100%;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
}

.basic-org-info {
  display: flex;
  gap: 20px;
  padding: 10px 0;
}

.avatar-section {
  flex-shrink: 0;
}

.info-section {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.user-names {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 15px;
}

.display-name {
  font-size: 20px;
  font-weight: bold;
  color: #303133;
}

.username {
  font-size: 14px;
  color: #909399;
}

.info-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid #ebeef5;
}

.info-item:last-child {
  border-bottom: none;
}

.info-item .label {
  font-size: 14px;
  color: #606266;
  font-weight: 500;
}

.info-item .value {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
}

.system-actions {
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.system-info {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 0;
}

.statistics-row {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding: 10px 0;
}

.stat-item {
  flex: 1;
  min-width: 0;
}

:deep(.el-statistic__head) {
  font-size: 14px;
  color: #909399;
  margin-bottom: 8px;
}

:deep(.el-statistic__content) {
  font-size: 28px;
  font-weight: bold;
  color: #303133;
}

:deep(.el-table) {
  border-radius: 4px;
  overflow: hidden;
}

:deep(.el-table th) {
  background-color: #fafafa;
  font-weight: 600;
}

@media (max-width: 768px) {
  .basic-org-info {
    flex-direction: column;
    align-items: center;
  }

  .info-item {
    flex-direction: column;
    align-items: flex-start;
    gap: 5px;
  }

  .statistics-row {
    flex-direction: column;
  }

  .stat-item {
    width: 100%;
  }
}
</style>
