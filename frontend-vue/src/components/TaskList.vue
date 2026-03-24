<template>
  <div class="task-list-comp">
    <div class="toolbar">
      <el-button @click="loadTasks" :loading="loading"><el-icon><Refresh /></el-icon>刷新</el-button>
      <el-select v-model="filterType" placeholder="任务类型" style="width: 150px; margin-left: 10px;" @change="loadTasks">
        <el-option label="全部" value="" /><el-option label="DINO 检测" value="DINO_DETECTION" /><el-option label="VLM 清洗" value="VLM_CLEANING" /><el-option label="YOLO 检测" value="YOLO_DETECTION" />
      </el-select>
      <el-select v-model="filterStatus" placeholder="任务状态" style="width: 150px; margin-left: 10px;" @change="loadTasks">
        <el-option label="全部" value="" /><el-option label="等待中" value="PENDING" /><el-option label="运行中" value="RUNNING" /><el-option label="已完成" value="COMPLETED" /><el-option label="失败" value="FAILED" /><el-option label="已取消" value="CANCELLED" />
      </el-select>
    </div>
    <el-table :data="tasks" v-loading="loading" style="width: 100%">
      <el-table-column prop="taskId" label="任务ID" width="200" />
      <el-table-column prop="taskType" label="任务类型" width="150"><template #default="{ row }"><el-tag :type="getTaskTypeColor(row.taskType)" size="small">{{ getTaskTypeText(row.taskType) }}</el-tag></template></el-table-column>
      <el-table-column prop="status" label="状态" width="120"><template #default="{ row }"><el-tag :type="getStatusType(row.status)" size="small">{{ getStatusText(row.status) }}</el-tag></template></el-table-column>
      <el-table-column prop="progress" label="进度" width="200"><template #default="{ row }"><el-progress :percentage="row.progress" :status="getProgressStatus(row.status)" /></template></el-table-column>
      <el-table-column prop="totalImages" label="总数" width="100" />
      <el-table-column prop="processedImages" label="已处理" width="100" />
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="200" fixed="right"><template #default="{ row }">
        <el-button type="primary" link size="small" @click="viewTask(row)">查看</el-button>
        <el-button v-if="row.status === 'RUNNING'" type="danger" link size="small" @click="cancelTask(row)">取消</el-button>
        <el-button v-if="row.status === 'COMPLETED'" type="success" link size="small" @click="downloadResults(row)">下载</el-button>
      </template></el-table-column>
    </el-table>
    <div v-if="tasks.length === 0" class="empty-state"><el-empty description="暂无任务记录" /></div>
    <el-dialog v-model="taskDetailVisible" title="任务详情" width="70%">
      <div v-if="currentTask">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="任务ID">{{ currentTask.taskId }}</el-descriptions-item>
          <el-descriptions-item label="任务类型"><el-tag :type="getTaskTypeColor(currentTask.taskType)" size="small">{{ getTaskTypeText(currentTask.taskType) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="getStatusType(currentTask.status)" size="small">{{ getStatusText(currentTask.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="进度">{{ currentTask.progress }}%</el-descriptions-item>
          <el-descriptions-item label="总数">{{ currentTask.totalImages }}</el-descriptions-item>
          <el-descriptions-item label="已处理">{{ currentTask.processedImages }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ currentTask.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ currentTask.completedAt || '-' }}</el-descriptions-item>
        </el-descriptions>
        <div class="section-label" style="margin-top: 20px;">任务参数</div>
        <pre class="task-params">{{ JSON.stringify(currentTask.parameters, null, 2) }}</pre>
        <el-alert v-if="currentTask.errorMessage" type="error" :title="currentTask.errorMessage" :closable="false" style="margin-top: 16px;" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { algorithmAPI } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'

const props = defineProps({ projectId: { type: [String, Number], required: true } })
const loading = ref(false); const tasks = ref([]); const filterType = ref(''); const filterStatus = ref(''); const taskDetailVisible = ref(false); const currentTask = ref(null)
const getTaskTypeColor = (type) => ({ 'DINO_DETECTION': 'primary', 'VLM_CLEANING': 'warning', 'YOLO_DETECTION': 'success' }[type] || 'info')
const getTaskTypeText = (type) => ({ 'DINO_DETECTION': 'DINO 检测', 'VLM_CLEANING': 'VLM 清洗', 'YOLO_DETECTION': 'YOLO 检测' }[type] || type)
const getStatusType = (status) => ({ 'PENDING': 'info', 'RUNNING': 'primary', 'COMPLETED': 'success', 'FAILED': 'danger', 'CANCELLED': 'warning' }[status] || 'info')
const getStatusText = (status) => ({ 'PENDING': '等待中', 'RUNNING': '运行中', 'COMPLETED': '已完成', 'FAILED': '失败', 'CANCELLED': '已取消' }[status] || status)
const getProgressStatus = (status) => ({ 'COMPLETED': 'success', 'FAILED': 'exception', 'CANCELLED': 'warning' }[status] || '')
const loadTasks = async () => {
  if (!props.projectId || props.projectId === 'null') return
  try { loading.value = true; const params = { projectId: props.projectId }; if (filterType.value) params.taskType = filterType.value; if (filterStatus.value) params.status = filterStatus.value; const response = await algorithmAPI.getTasks(params); tasks.value = response.data.tasks || [] } catch (error) { ElMessage.error('加载任务列表失败') } finally { loading.value = false }
}
const viewTask = (task) => { currentTask.value = task; taskDetailVisible.value = true }
const cancelTask = async (task) => { try { await ElMessageBox.confirm('确定要取消该任务吗？', '提示', { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }); let cancelAPI; if (task.taskType === 'DINO_DETECTION') cancelAPI = algorithmAPI.cancelDinoTask(task.taskId); else if (task.taskType === 'VLM_CLEANING') cancelAPI = algorithmAPI.cancelVlmTask(task.taskId); else if (task.taskType === 'YOLO_DETECTION') cancelAPI = algorithmAPI.cancelYoloTask(task.taskId); await cancelAPI; ElMessage.success('任务已取消'); loadTasks() } catch (error) { if (error !== 'cancel') ElMessage.error('取消任务失败') } }
const downloadResults = async (task) => { try { let resultsAPI; if (task.taskType === 'DINO_DETECTION') resultsAPI = algorithmAPI.getDinoTaskResults(task.taskId); else if (task.taskType === 'VLM_CLEANING') resultsAPI = algorithmAPI.getVlmTaskResults(task.taskId); else if (task.taskType === 'YOLO_DETECTION') resultsAPI = algorithmAPI.getYoloTaskResults(task.taskId); const response = await resultsAPI; const results = response.data.results || []; const dataStr = JSON.stringify(results, null, 2); const dataBlob = new Blob([dataStr], { type: 'application/json' }); const url = URL.createObjectURL(dataBlob); const link = document.createElement('a'); link.href = url; link.download = `${task.taskId}_results.json`; link.click(); URL.revokeObjectURL(url); ElMessage.success('结果下载成功') } catch (error) { ElMessage.error('下载结果失败') } }
onMounted(() => { loadTasks() })
</script>

<style scoped>
.task-list-comp { }
.toolbar { margin-bottom: 16px; display: flex; align-items: center; }
.empty-state { padding: 48px 0; }
.section-label { font-size: 13px; font-weight: 500; color: var(--gray-600); margin-bottom: 8px; }
.task-params { background: var(--gray-50); padding: 12px 16px; border-radius: var(--radius-md); font-size: 12px; max-height: 300px; overflow-y: auto; font-family: 'SF Mono', Monaco, monospace; }
</style>
