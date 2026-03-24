<template>
  <div class="vlm-task">
    <p class="section-desc">使用视觉语言模型（VLM）清洗标注数据，提高数据质量</p>
    <el-form :model="form" label-width="120px">
      <el-form-item label="清洗模式"><el-radio-group v-model="form.mode"><el-radio label="detections">基于检测结果清洗</el-radio><el-radio label="images">基于图片路径清洗</el-radio></el-radio-group></el-form-item>
      <el-form-item v-if="form.mode === 'detections'" label="检测结果"><el-select v-model="form.detections" multiple filterable placeholder="请选择检测结果" style="width: 100%;"><el-option v-for="detection in availableDetections" :key="detection.id" :label="`${detection.imageName} - ${detection.label}`" :value="detection.id" /></el-select></el-form-item>
      <el-form-item v-if="form.mode === 'images'" label="选择图片"><el-select v-model="form.imagePaths" multiple filterable placeholder="请选择图片" style="width: 100%;"><el-option v-for="image in availableImages" :key="image.path" :label="image.name" :value="image.path" /></el-select></el-form-item>
      <el-row :gutter="20">
        <el-col :span="12"><el-form-item label="VLM 模型"><el-select v-model="form.model" style="width: 100%;"><el-option label="GPT-4V" value="gpt-4-vision-preview" /><el-option label="GPT-4V (Turbo)" value="gpt-4o" /><el-option label="Claude 3.5 Sonnet" value="claude-3-5-sonnet-20241022" /></el-select></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="最大 Token"><el-input-number v-model="form.maxTokens" :min="100" :max="4000" :step="100" style="width: 100%;" /></el-form-item></el-col>
      </el-row>
      <el-form-item label="最小尺寸"><el-input-number v-model="form.minDim" :min="10" :max="500" :step="10" style="width: 200px;" /><span class="form-tip-inline">像素（过滤过小的检测框）</span></el-form-item>
      <el-form-item label="类别定义">
        <el-select v-model="form.labelDefinitions" multiple filterable allow-create placeholder="请选择或输入类别定义" style="width: 100%;"><el-option v-for="(definition, label) in availableLabels" :key="label" :label="`${label}: ${definition}`" :value="label" /></el-select>
        <div class="form-tip">为每个类别提供详细定义，帮助 VLM 准确判断</div>
      </el-form-item>
      <el-form-item><el-button type="primary" size="large" @click="handleRun" :loading="running" :disabled="!canRun"><el-icon><VideoPlay /></el-icon>开始清洗</el-button></el-form-item>
    </el-form>
    <div v-if="running" class="task-status"><el-progress :percentage="progress" :status="progressStatus" /><div class="status-info"><span>{{ currentStatus }}</span><span>{{ processedItems }} / {{ totalItems }} 项</span></div></div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { algorithmAPI, projectAPI } from '@/api'
import { ElMessage } from 'element-plus'

const props = defineProps({ project: { type: Object, required: true } })
const emit = defineEmits(['task-created'])
const form = ref({ mode: 'detections', detections: [], imagePaths: [], model: 'gpt-4-vision-preview', maxTokens: 1000, minDim: 32, labelDefinitions: [] })
const availableLabels = ref({}); const availableDetections = ref([]); const availableImages = ref([]); const running = ref(false); const progress = ref(0); const currentStatus = ref(''); const processedItems = ref(0); const totalItems = ref(0)
const canRun = computed(() => { if (form.value.mode === 'detections') return form.value.detections.length > 0; else return form.value.imagePaths.length > 0 })
const loadAvailableLabels = () => { availableLabels.value = props.project.labels || {} }
const loadAvailableDetections = async () => { if (!props.project || !props.project.id || props.project.id === 'null') return; try { const response = await projectAPI.getProjectImages(props.project.id, { page: 1, pageSize: 1000 }); const images = response.data.images || []; availableDetections.value = []; images.forEach(img => { if (img.detections && img.detections.length > 0) { img.detections.forEach((det, idx) => { availableDetections.value.push({ id: `${img.path}_${idx}`, imagePath: img.path, imageName: img.name, label: det.label, bbox: det.bbox }) }) } }) } catch (error) { console.error('加载检测结果失败', error) } }
const loadAvailableImages = async () => { try { const response = await projectAPI.getProjectImages(props.project.id, { page: 1, pageSize: 1000 }); availableImages.value = response.data.images || [] } catch (error) { console.error('加载图片列表失败', error) } }
const handleRun = async () => {
  if (!canRun.value) { ElMessage.warning('请选择要清洗的数据'); return }
  try { running.value = true; progress.value = 0; currentStatus.value = '正在初始化任务...'; processedItems.value = 0; totalItems.value = 0
    const requestData = { projectId: props.project.id, model: form.value.model, maxTokens: form.value.maxTokens, minDim: form.value.minDim }
    if (form.value.mode === 'detections') { const detections = availableDetections.value.filter(d => form.value.detections.includes(d.id)); requestData.detections = detections.map(d => ({ imagePath: d.imagePath, bbox: d.bbox, label: d.label })); totalItems.value = detections.length } else { requestData.imagePaths = form.value.imagePaths; totalItems.value = form.value.imagePaths.length }
    const labelDefinitions = {}; form.value.labelDefinitions.forEach(label => { if (availableLabels.value[label]) labelDefinitions[label] = availableLabels.value[label] }); requestData.labelDefinitions = labelDefinitions
    const response = await algorithmAPI.runVlmCleaning(requestData); const taskId = response.data.taskId; ElMessage.success('VLM 清洗任务已创建'); emit('task-created', taskId); await pollTaskStatus(taskId)
  } catch (error) { ElMessage.error('创建任务失败：' + (error.message || '未知错误')) } finally { running.value = false }
}
const pollTaskStatus = async (taskId) => { const pollInterval = setInterval(async () => { try { const response = await algorithmAPI.getVlmTaskStatus(taskId); const task = response.data; progress.value = task.progress; currentStatus.value = task.status === 'RUNNING' ? '正在清洗...' : task.status; processedItems.value = task.processedImages || 0; if (task.status === 'COMPLETED') { clearInterval(pollInterval); ElMessage.success('VLM 清洗完成'); running.value = false } else if (task.status === 'FAILED') { clearInterval(pollInterval); ElMessage.error('VLM 清洗失败：' + (task.errorMessage || '未知错误')); running.value = false } } catch (error) { clearInterval(pollInterval); console.error('轮询任务状态失败', error); running.value = false } }, 2000) }
const progressStatus = computed(() => { if (progress.value === 100) return 'success'; return undefined })
onMounted(() => { loadAvailableLabels(); loadAvailableDetections(); loadAvailableImages() })
</script>

<style scoped>
.vlm-task { }
.section-desc { color: var(--gray-500); font-size: 13px; margin-bottom: 20px; }
.form-tip { font-size: 12px; color: var(--gray-400); margin-top: 4px; }
.form-tip-inline { margin-left: 10px; font-size: 12px; color: var(--gray-400); }
.task-status { margin-top: 24px; padding: 16px; background: var(--gray-50); border-radius: var(--radius-md); }
.status-info { display: flex; justify-content: space-between; margin-top: 10px; font-size: 13px; color: var(--gray-600); }
</style>
