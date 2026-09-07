<template>
  <div class="dashboard">
    <PageHeading
      eyebrow="OPERATIONS OVERVIEW"
      title="运营概览"
      description="集中查看项目规模、数据资产与智能标注任务的最新运行状态。"
    >
      <div class="live-summary" aria-label="闭环任务概览">
        <span><i /> 闭环任务</span>
        <strong>{{ stats.runningTasks }} / {{ stats.completedTasks }}</strong>
        <small>运行中 / 已完成</small>
      </div>
    </PageHeading>

    <section class="status-cards" aria-label="组织数据指标">
      <MetricCard label="总项目数" :value="stats.totalProjects" hint="当前组织项目" mark="Σ" />
      <MetricCard label="总图片数" :value="stats.totalImages" hint="已接入数据资产" tone="running" mark="▧" />
      <MetricCard label="运行中任务" :value="stats.runningTasks" hint="正在执行的任务" tone="warning" mark="▶" />
      <MetricCard label="已完成任务" :value="stats.completedTasks" hint="已经完成的任务" tone="danger" mark="✓" />
    </section>
    
    <PanelCard title="最近项目" description="最近更新的项目与处理进度" :padded="false">
      <template #actions><PlatformButton variant="secondary" @click="goToProjects">查看全部</PlatformButton></template>
      <el-table :data="recentProjects" style="width: 100%">
        <el-table-column prop="name" label="项目名称" />
        <el-table-column prop="status" label="状态">
          <template #default="{ row }">
            <StatusPill :tone="getStatusTone(row.status)">
              {{ getStatusText(row.status) }}
            </StatusPill>
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
    </PanelCard>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { projectAPI, userAPI } from '@/api'
import { getProjectStatusText, getProjectStatusType } from '@/utils/projectStatus'
import MetricCard from '@/components/platform-ui/MetricCard.vue'
import PageHeading from '@/components/platform-ui/PageHeading.vue'
import PanelCard from '@/components/platform-ui/PanelCard.vue'
import PlatformButton from '@/components/platform-ui/PlatformButton.vue'
import StatusPill from '@/components/platform-ui/StatusPill.vue'

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

const getStatusTone = (status) => ({
  success: 'success', danger: 'danger', warning: 'warning', primary: 'active', info: 'neutral'
}[getStatusType(status)] || 'neutral')

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

.status-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 13px;
  margin-bottom: 24px;
}

.live-summary {
  display: grid;
  grid-template-columns: auto auto;
  gap: 3px 18px;
  min-width: 190px;
  padding: 14px 17px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 14px;
  box-shadow: var(--shadow-sm);
}

.live-summary span, .live-summary small { color: var(--ink-500); font-size: 9px; }
.live-summary span i { display: inline-block; width: 6px; height: 6px; margin-right: 5px; background: var(--success); border-radius: 50%; box-shadow: 0 0 0 4px rgba(24,168,115,.1); }
.live-summary strong { grid-row: 1 / 3; grid-column: 2; align-self: center; font: 800 24px "DIN Alternate", sans-serif; }

@media (max-width: 1050px) {
  .status-cards { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 620px) {
  .status-cards { grid-template-columns: 1fr; }
}
</style>
