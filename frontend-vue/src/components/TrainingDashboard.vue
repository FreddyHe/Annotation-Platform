<template>
  <div class="training-dashboard">
    <el-row :gutter="20">
      <el-col :span="8">
        <el-card>
          <template #header><div class="card-header"><el-icon><Setting /></el-icon><span class="card-title">训练参数配置</span></div></template>
          <el-form :model="trainingForm" label-width="120px">
            <el-form-item label="选择项目"><el-select v-model="trainingForm.projectId" placeholder="请选择项目" style="width: 100%" @change="handleProjectChange"><el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" /></el-select></el-form-item>
            <el-form-item label="训练轮数"><el-input-number v-model="trainingForm.epochs" :min="10" :max="500" :step="10" style="width: 100%" /><div class="form-tip">建议首次训练使用 100 轮</div></el-form-item>
            <el-form-item label="批次大小"><el-select v-model="trainingForm.batchSize" placeholder="请选择" style="width: 100%"><el-option label="8" :value="8" /><el-option label="16" :value="16" /><el-option label="32" :value="32" /><el-option label="64" :value="64" /></el-select><div class="form-tip">根据 GPU 显存选择</div></el-form-item>
            <el-form-item label="图片尺寸"><el-select v-model="trainingForm.imageSize" placeholder="请选择" style="width: 100%"><el-option label="416" :value="416" /><el-option label="512" :value="512" /><el-option label="640" :value="640" /><el-option label="800" :value="800" /><el-option label="1024" :value="1024" /></el-select><div class="form-tip">640 是标准尺寸</div></el-form-item>
            <el-form-item label="模型类型"><el-select v-model="trainingForm.modelType" placeholder="请选择" style="width: 100%"><el-option label="YOLOv8n (Nano - 最快)" value="yolov8n.pt" /><el-option label="YOLOv8s (Small)" value="yolov8s.pt" /><el-option label="YOLOv8m (Medium)" value="yolov8m.pt" /><el-option label="YOLOv8l (Large)" value="yolov8l.pt" /><el-option label="YOLOv8x (Extra Large - 最强)" value="yolov8x.pt" /></el-select></el-form-item>
            <el-form-item label="GPU 设备"><el-input v-model="trainingForm.device" placeholder="例如: 0 或 0,1" style="width: 100%" /><div class="form-tip">多 GPU 使用逗号分隔</div></el-form-item>
            <el-form-item><el-button type="primary" :loading="isStarting" :disabled="!canStartTraining" @click="startTraining" style="width: 100%" size="large"><el-icon><VideoPlay /></el-icon>拉取数据并开始训练</el-button></el-form-item>
          </el-form>
        </el-card>
        <el-card style="margin-top: 16px;">
          <template #header><div class="card-header"><el-icon><DataLine /></el-icon><span class="card-title">数据统计</span></div></template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="项目名称">{{ currentProject?.name || '-' }}</el-descriptions-item>
            <el-descriptions-item label="Label Studio ID">{{ currentProject?.labelStudioProjectId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="已标注图片">{{ stats.annotatedImages || '-' }}</el-descriptions-item>
            <el-descriptions-item label="标注框总数">{{ stats.totalAnnotations || '-' }}</el-descriptions-item>
            <el-descriptions-item label="类别数量">{{ stats.labelCount || '-' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
      <el-col :span="16">
        <el-card>
          <template #header>
            <div class="card-header">
              <div style="display: flex; align-items: center; gap: 8px;"><el-icon><Monitor /></el-icon><span class="card-title">实时监控面板</span></div>
              <div class="header-actions">
                <el-tag :type="getStatusTagType(currentTraining?.status)" size="default">{{ getStatusText(currentTraining?.status) }}</el-tag>
                <el-button v-if="currentTraining?.status === 'RUNNING'" type="danger" size="small" @click="cancelTraining"><el-icon><VideoPause /></el-icon>取消训练</el-button>
              </div>
            </div>
          </template>
          <div class="monitor-content">
            <div v-if="!currentTraining" class="empty-state"><el-icon :size="64" color="var(--gray-300)"><Monitor /></el-icon><p class="empty-title">暂无训练任务</p><p class="empty-tip">请配置参数并启动训练</p></div>
            <div v-else class="training-monitor">
              <div class="training-info">
                <el-row :gutter="20">
                  <el-col :span="6"><div class="info-item"><div class="info-label">任务 ID</div><div class="info-value">{{ currentTraining.taskId }}</div></div></el-col>
                  <el-col :span="6"><div class="info-item"><div class="info-label">开始时间</div><div class="info-value">{{ formatTime(currentTraining.startedAt) }}</div></div></el-col>
                  <el-col :span="6"><div class="info-item"><div class="info-label">运行时长</div><div class="info-value">{{ runningDuration }}</div></div></el-col>
                  <el-col :span="6"><div class="info-item"><div class="info-label">当前 Epoch</div><div class="info-value">{{ currentEpoch }} / {{ trainingForm.epochs }}</div></div></el-col>
                </el-row>
              </div>
              <div class="log-container">
                <div class="log-header"><span>训练日志</span><el-button type="primary" size="small" @click="clearLogs"><el-icon><Delete /></el-icon>清空日志</el-button></div>
                <div ref="logContainer" class="log-content" :class="{ 'log-empty': logs.length === 0 }">
                  <div v-if="logs.length === 0" class="log-empty-state">等待日志输出...</div>
                  <div v-for="(log, index) in logs" :key="index" class="log-line">{{ log }}</div>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Setting, Monitor, DataLine, VideoPlay, VideoPause, Delete } from '@element-plus/icons-vue'
import { projectAPI, trainingAPI, labelStudioAPI } from '@/api'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const projects = ref([]); const currentProject = ref(null); const currentTraining = ref(null); const logs = ref([]); const logContainer = ref(null); const isStarting = ref(false); const logPollingTimer = ref(null); const durationTimer = ref(null); const runningDuration = ref('00:00:00'); const currentEpoch = ref(0)
const trainingForm = ref({ projectId: null, epochs: 100, batchSize: 16, imageSize: 640, modelType: 'yolov8n.pt', device: '0' })
const stats = ref({ annotatedImages: 0, totalAnnotations: 0, labelCount: 0 })
const canStartTraining = computed(() => trainingForm.value.projectId && stats.value.annotatedImages >= 3 && !isStarting.value && (!currentTraining.value || currentTraining.value.status !== 'RUNNING'))
const loadProjects = async () => { try { const res = await projectAPI.getProjects(); projects.value = res.data || [] } catch (error) { ElMessage.error('加载项目列表失败') } }
const handleProjectChange = async (projectId) => { currentProject.value = projects.value.find(p => p.id === projectId); if (currentProject.value) await loadProjectStats() }
const loadProjectStats = async () => { try { const res = await projectAPI.getProjectStats(currentProject.value.id); stats.value = res.data || {} } catch (error) { console.error('加载项目统计失败', error) } }
const startTraining = async () => {
  try { isStarting.value = true; const lsToken = await getLabelStudioToken(); if (!lsToken) { ElMessage.error('无法获取 Label Studio Token'); return }
    const res = await trainingAPI.startTraining({ projectId: trainingForm.value.projectId, labelStudioProjectId: currentProject.value.labelStudioProjectId, lsToken, epochs: trainingForm.value.epochs, batchSize: trainingForm.value.batchSize, imageSize: trainingForm.value.imageSize, modelType: trainingForm.value.modelType, device: trainingForm.value.device })
    currentTraining.value = res.data; logs.value = []; currentEpoch.value = 0; ElMessage.success('训练任务已启动'); startLogPolling(); startDurationTimer()
  } catch (error) { ElMessage.error('启动训练失败: ' + (error.response?.data?.message || error.message)) } finally { isStarting.value = false }
}
const getLabelStudioToken = async () => { try { const res = await labelStudioAPI.getLoginUrl({ projectId: currentProject.value.id }); return res.data?.lsToken } catch (error) { console.error('获取 Label Studio Token 失败', error); return null } }
const startLogPolling = () => { if (logPollingTimer.value) clearInterval(logPollingTimer.value); logPollingTimer.value = setInterval(async () => { try { const res = await trainingAPI.getTrainingLog(currentTraining.value.taskId); const logContent = res.data?.log_content || ''; if (logContent) { const newLogs = logContent.split('\n').filter(line => line.trim()); const lastLogIndex = logs.value.length > 0 ? logs.value.findIndex(log => log === newLogs[0]) : -1; if (lastLogIndex === -1) logs.value = [...logs.value, ...newLogs]; else logs.value = [...logs.value.slice(0, lastLogIndex), ...newLogs]; await nextTick(); scrollToBottom(); parseCurrentEpoch() }; const recordRes = await trainingAPI.getTrainingRecordByTaskId(currentTraining.value.taskId); currentTraining.value = recordRes.data; if (currentTraining.value.status !== 'RUNNING') { stopLogPolling(); stopDurationTimer(); if (currentTraining.value.status === 'COMPLETED') ElMessage.success('训练完成！'); else if (currentTraining.value.status === 'FAILED') ElMessage.error('训练失败: ' + currentTraining.value.errorMessage) } } catch (error) { console.error('轮询日志失败', error) } }, 3000) }
const stopLogPolling = () => { if (logPollingTimer.value) { clearInterval(logPollingTimer.value); logPollingTimer.value = null } }
const startDurationTimer = () => { if (durationTimer.value) clearInterval(durationTimer.value); const startTime = new Date(currentTraining.value.startedAt).getTime(); durationTimer.value = setInterval(() => { const diff = Math.floor((Date.now() - startTime) / 1000); const hours = Math.floor(diff / 3600); const minutes = Math.floor((diff % 3600) / 60); const seconds = diff % 60; runningDuration.value = String(hours).padStart(2, '0') + ':' + String(minutes).padStart(2, '0') + ':' + String(seconds).padStart(2, '0') }, 1000) }
const stopDurationTimer = () => { if (durationTimer.value) { clearInterval(durationTimer.value); durationTimer.value = null } }
const scrollToBottom = () => { if (logContainer.value) logContainer.value.scrollTop = logContainer.value.scrollHeight }
const parseCurrentEpoch = () => { const epochPattern = /Epoch\s+(\d+)\/(\d+)/; for (let i = logs.value.length - 1; i >= 0; i--) { const match = logs.value[i].match(epochPattern); if (match) { currentEpoch.value = parseInt(match[1]); break } } }
const clearLogs = () => { logs.value = [] }
const cancelTraining = async () => { try { await ElMessageBox.confirm('确定要取消当前训练吗？', '确认取消', { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }); await trainingAPI.cancelTraining(currentTraining.value.id); ElMessage.success('训练已取消'); stopLogPolling(); stopDurationTimer() } catch (error) { if (error !== 'cancel') ElMessage.error('取消训练失败') } }
const getStatusTagType = (status) => ({ 'PENDING': 'info', 'RUNNING': 'warning', 'COMPLETED': 'success', 'FAILED': 'danger', 'CANCELLED': 'info' }[status] || 'info')
const getStatusText = (status) => ({ 'PENDING': '等待中', 'RUNNING': '运行中', 'COMPLETED': '已完成', 'FAILED': '失败', 'CANCELLED': '已取消' }[status] || status)
const formatTime = (time) => { if (!time) return '-'; return new Date(time).toLocaleString('zh-CN') }
onMounted(() => { loadProjects() })
onUnmounted(() => { stopLogPolling(); stopDurationTimer() })
</script>

<style scoped>
.training-dashboard { }
.card-header { display: flex; align-items: center; gap: 8px; justify-content: space-between; }
.card-title { font-size: 14px; font-weight: 500; color: var(--gray-900); }
.header-actions { display: flex; align-items: center; gap: 10px; }
.form-tip { font-size: 12px; color: var(--gray-400); margin-top: 4px; }
.monitor-content { min-height: 480px; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 480px; color: var(--gray-400); }
.empty-title { margin: 12px 0 4px; font-size: 15px; }
.empty-tip { font-size: 13px; color: var(--gray-300); }
.training-monitor { display: flex; flex-direction: column; gap: 16px; }
.training-info { background: var(--brand-50); padding: 16px; border-radius: var(--radius-md); border: 0.5px solid var(--brand-100); }
.info-item { text-align: center; }
.info-label { font-size: 12px; color: var(--gray-500); margin-bottom: 6px; }
.info-value { font-size: 13px; font-weight: 600; color: var(--gray-900); }
.log-container { background: #1a1a2e; border-radius: var(--radius-lg); overflow: hidden; flex: 1; display: flex; flex-direction: column; min-height: 360px; }
.log-header { display: flex; justify-content: space-between; align-items: center; padding: 10px 16px; background: #16162a; border-bottom: 1px solid #2a2a4a; color: #a0a0c0; font-size: 13px; font-weight: 500; }
.log-content { flex: 1; overflow-y: auto; padding: 12px 16px; font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace; font-size: 12px; line-height: 1.7; color: #c8c8e0; background: #1a1a2e; scroll-behavior: smooth; }
.log-content::-webkit-scrollbar { width: 6px; }
.log-content::-webkit-scrollbar-track { background: #1a1a2e; }
.log-content::-webkit-scrollbar-thumb { background: #3a3a5a; border-radius: 3px; }
.log-empty { display: flex; align-items: center; justify-content: center; }
.log-empty-state { color: #505070; font-style: italic; }
.log-line { margin: 1px 0; white-space: pre-wrap; word-break: break-all; }
.log-line:hover { background: #20203a; }
</style>
