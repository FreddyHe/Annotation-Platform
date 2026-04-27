<template>
  <div class="incremental-review">
    <div class="review-toolbar">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="低-B 待审数据会同步到增量 Label Studio 项目；只有全量审核完成的增量项目会进入下一次训练数据集。"
      />
      <el-button :loading="loading" @click="loadData">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
      <el-button :loading="syncing" @click="syncFlywheel">
        同步回流数据
      </el-button>
    </div>

    <el-alert
      v-if="lastSync"
      type="success"
      :closable="false"
      :title="`可信池 ${lastSync.trustedSyncedTotal || 0}/${lastSync.trustedTotal || 0} 已入主 LS，本次新增 ${lastSync.trustedSyncedThisTime || lastSync.trustedSynced || 0} 张；LOW_B 本次入批 ${lastSync.lowBSyncedThisTime || lastSync.lowBSynced || 0} 张，等待 ${lastSync.waitingLowB || 0}/${lastSync.lowBBatchSize || 100}`"
    />

    <div class="review-grid">
      <el-card shadow="never">
        <template #header>
          <span class="card-title">待审增量项目</span>
        </template>
        <el-table :data="pending" v-loading="loading" stripe empty-text="暂无待审批次">
          <el-table-column prop="projectName" label="项目" min-width="220" show-overflow-tooltip />
          <el-table-column prop="batchNumber" label="批次" width="80" />
          <el-table-column label="进度" width="160">
            <template #default="{ row }">{{ row.reviewedTasks || 0 }} / {{ row.expectedTasks || 0 }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="row.status === 'PARTIAL' ? 'warning' : 'info'" size="small">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="openLsProject(row)">
                打开 LS 审核
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <span class="card-title">已纳入训练候选</span>
        </template>
        <el-table :data="reviewed" v-loading="loading" stripe empty-text="暂无已审批次">
          <el-table-column prop="projectName" label="项目" min-width="220" show-overflow-tooltip />
          <el-table-column prop="batchNumber" label="批次" width="80" />
          <el-table-column prop="reviewedTasks" label="任务数" width="100" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag type="success" size="small">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="openLsProject(row)">
                打开 LS
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { labelStudioAPI, projectAPI } from '@/api'

const props = defineProps({
  project: { type: Object, required: true }
})

const loading = ref(false)
const syncing = ref(false)
const status = ref({})
const lastSync = ref(null)

const pending = computed(() => status.value.pendingIncrementals || [])
const reviewed = computed(() => status.value.reviewedIncrementals || [])

const statusText = (value) => {
  const map = {
    PENDING_REVIEW: '待审核',
    PARTIAL: '部分审核',
    REVIEWED: '已审核'
  }
  return map[value] || value || '-'
}

const loadData = async () => {
  if (!props.project?.id) return
  loading.value = true
  try {
    const response = await projectAPI.getIncrementals(props.project.id)
    status.value = response.data || {}
  } finally {
    loading.value = false
  }
}

const syncFlywheel = async () => {
  if (!props.project?.id) return
  syncing.value = true
  try {
    const response = await projectAPI.syncFlywheelData(props.project.id)
    lastSync.value = response.data || {}
    ElMessage.success('回流数据同步完成')
    await loadData()
  } finally {
    syncing.value = false
  }
}

const openLsProject = async (row) => {
  if (!row?.lsProjectId) {
    ElMessage.error('缺少 Label Studio 项目 ID')
    return
  }
  try {
    const response = await labelStudioAPI.getLoginUrl({
      returnUrl: `/projects/${row.lsProjectId}/data`
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
    if (parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1') {
      parsed.hostname = window.location.hostname
      return parsed.toString()
    }
  } catch (error) {
    return url
  }
  return url
}

watch(() => props.project.id, loadData)
onMounted(loadData)
</script>

<style scoped>
.incremental-review {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.review-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 12px;
  align-items: center;
}

.review-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
}

@media (max-width: 1100px) {
  .review-grid,
  .review-toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
