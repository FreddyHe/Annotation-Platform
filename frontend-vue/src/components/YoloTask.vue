<template>
  <div class="yolo-task">
    <p class="section-desc">使用 YOLO 系列模型进行快速目标检测</p>
    <el-form :model="form" label-width="120px">
      <el-row :gutter="20">
        <el-col :span="12"><el-form-item label="模型大小"><el-select v-model="form.modelSize" style="width: 100%;"><el-option label="YOLOv8n (Nano)" value="n" /><el-option label="YOLOv8s (Small)" value="s" /><el-option label="YOLOv8m (Medium)" value="m" /><el-option label="YOLOv8l (Large)" value="l" /><el-option label="YOLOv8x (XLarge)" value="x" /></el-select></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="检测标签"><el-select v-model="form.labels" multiple filterable allow-create placeholder="请输入或选择检测标签" style="width: 100%;"><el-option v-for="label in availableLabels" :key="label" :label="label" :value="label" /></el-select></el-form-item></el-col>
      </el-row>
      <el-row :gutter="20">
        <el-col :span="12"><el-form-item label="置信度阈值"><el-slider v-model="form.confidenceThreshold" :min="0" :max="1" :step="0.05" :format-tooltip="formatThreshold" style="width: 300px;" /><span class="slider-value">{{ form.confidenceThreshold }}</span></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="IOU 阈值"><el-slider v-model="form.iouThreshold" :min="0" :max="1" :step="0.05" :format-tooltip="formatThreshold" style="width: 300px;" /><span class="slider-value">{{ form.iouThreshold }}</span></el-form-item></el-col>
      </el-row>
      <el-form-item label="图片选择"><el-radio-group v-model="imageSelectionMode"><el-radio label="all">全部图片</el-radio><el-radio label="unprocessed">未处理图片</el-radio><el-radio label="selected">选择图片</el-radio></el-radio-group></el-form-item>
      <el-form-item v-if="imageSelectionMode === 'selected'" label="选择图片"><el-select v-model="form.imagePaths" multiple filterable placeholder="请选择图片" style="width: 100%;"><el-option v-for="image in availableImages" :key="image.path" :label="image.name" :value="image.path" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" size="large" @click="handleRun" :loading="running" :disabled="!canRun"><el-icon><VideoPlay /></el-icon>开始检测</el-button></el-form-item>
    </el-form>
    <div v-if="running" class="task-status"><el-progress :percentage="progress" :status="progressStatus" /><div class="status-info"><span>{{ currentStatus }}</span><span>{{ processedImages }} / {{ totalImages }} 张</span></div></div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { algorithmAPI, projectAPI } from '@/api'
import { ElMessage } from 'element-plus'

const props = defineProps({ project: { type: Object, required: true } })
const emit = defineEmits(['task-created'])
const form = ref({ modelSize: 's', labels: [], confidenceThreshold: 0.5, iouThreshold: 0.45, imagePaths: [] })
const imageSelectionMode = ref('all'); const availableLabels = ref([]); const availableImages = ref([]); const running = ref(false); const progress = ref(0); const currentStatus = ref(''); const processedImages = ref(0); const totalImages = ref(0)
const canRun = computed(() => form.value.labels.length > 0)
const formatThreshold = (value) => (value * 100).toFixed(0) + '%'
const loadAvailableLabels = () => { const labels = props.project.labels || {}; availableLabels.value = Object.keys(labels) }
const loadAvailableImages = async () => { if (!props.project || !props.project.id || props.project.id === 'null') return; try { const response = await projectAPI.getProjectImages(props.project.id, { page: 1, pageSize: 1000 }); availableImages.value = response.data.images || [] } catch (error) { console.error('加载图片列表失败', error) } }
const handleRun = async () => {
  if (!canRun.value) { ElMessage.warning('请至少选择一个检测标签'); return }
  try { running.value = true; progress.value = 0; currentStatus.value = '正在初始化任务...'; processedImages.value = 0; totalImages.value = 0
    let imagePaths = []; if (imageSelectionMode.value === 'all') imagePaths = availableImages.value.map(img => img.path); else if (imageSelectionMode.value === 'unprocessed') imagePaths = availableImages.value.filter(img => !img.processed).map(img => img.path); else imagePaths = form.value.imagePaths
    if (imagePaths.length === 0) { ElMessage.warning('没有可处理的图片'); return }; totalImages.value = imagePaths.length
    const response = await algorithmAPI.runYoloDetection({ projectId: props.project.id, imagePaths, modelSize: form.value.modelSize, labels: form.value.labels, confidenceThreshold: form.value.confidenceThreshold, iouThreshold: form.value.iouThreshold })
    const taskId = response.data.taskId; ElMessage.success('YOLO 检测任务已创建'); emit('task-created', taskId); await pollTaskStatus(taskId)
  } catch (error) { ElMessage.error('创建任务失败：' + (error.message || '未知错误')) } finally { running.value = false }
}
const pollTaskStatus = async (taskId) => { const pollInterval = setInterval(async () => { try { const response = await algorithmAPI.getYoloTaskStatus(taskId); const task = response.data; progress.value = task.progress; currentStatus.value = task.status === 'RUNNING' ? '正在检测...' : task.status; processedImages.value = task.processedImages || 0; if (task.status === 'COMPLETED') { clearInterval(pollInterval); ElMessage.success('YOLO 检测完成'); running.value = false } else if (task.status === 'FAILED') { clearInterval(pollInterval); ElMessage.error('YOLO 检测失败：' + (task.errorMessage || '未知错误')); running.value = false } } catch (error) { clearInterval(pollInterval); console.error('轮询任务状态失败', error); running.value = false } }, 2000) }
const progressStatus = computed(() => { if (progress.value === 100) return 'success'; return undefined })
onMounted(() => { loadAvailableLabels(); loadAvailableImages() })
</script>

<style scoped>
.yolo-task { }
.section-desc { color: var(--gray-500); font-size: 13px; margin-bottom: 20px; }
.slider-value { margin-left: 12px; font-size: 13px; color: var(--gray-600); font-weight: 500; }
.task-status { margin-top: 24px; padding: 16px; background: var(--gray-50); border-radius: var(--radius-md); }
.status-info { display: flex; justify-content: space-between; margin-top: 10px; font-size: 13px; color: var(--gray-600); }
</style>
