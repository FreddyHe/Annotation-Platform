<template>
  <div class="detection-results">
    <div class="toolbar">
      <el-button @click="loadResults" :loading="loading"><el-icon><Refresh /></el-icon>刷新</el-button>
      <el-select v-model="filterLabel" placeholder="筛选标签" clearable style="width: 150px; margin-left: 10px;" @change="loadResults">
        <el-option v-for="label in availableLabels" :key="label" :label="label" :value="label" />
      </el-select>
      <el-input-number v-model="minConfidence" :min="0" :max="1" :step="0.1" :precision="2" style="width: 130px; margin-left: 10px;" @change="loadResults" />
      <span class="toolbar-label">最小置信度</span>
    </div>
    <div class="result-summary">
      <el-tag type="info">Total {{ filteredResults.length }}</el-tag>
      <span>检测结果来自 DINO，VLM 只影响清洗/最终结果展示。</span>
    </div>
    <el-table :data="paginatedResults" v-loading="loading" style="width: 100%">
      <el-table-column prop="imageName" label="图片名称" min-width="170" show-overflow-tooltip />
      <el-table-column prop="label" label="标签" width="110"><template #default="{ row }"><el-tag type="primary" size="small">{{ row.label }}</el-tag></template></el-table-column>
      <el-table-column prop="confidence" label="置信度" width="110"><template #default="{ row }"><el-progress :percentage="Math.round(row.confidence * 100)" :stroke-width="8" /></template></el-table-column>
      <el-table-column label="预览" width="82"><template #default="{ row }"><el-button type="primary" link size="small" @click="previewImage(row)">预览</el-button></template></el-table-column>
    </el-table>
    <div v-if="results.length === 0" class="empty-state"><el-empty description="暂无检测结果" /></div>
    <el-pagination v-if="totalPages > 1" v-model:current-page="currentPage" v-model:page-size="pageSize" :page-sizes="[20, 50, 100]" :total="filteredResults.length" layout="total, sizes, prev, pager, next, jumper" style="margin-top: 16px; display: flex; justify-content: flex-end;" />
    <el-dialog v-model="previewVisible" title="检测预览" width="92vw" class="adaptive-preview-dialog">
      <div v-if="currentResult" class="preview-container">
        <div class="preview-canvas-wrap">
          <canvas ref="previewCanvasRef" class="preview-canvas" />
        </div>
        <div class="preview-info">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="图片名称">{{ currentResult.imageName }}</el-descriptions-item>
            <el-descriptions-item label="标签"><el-tag type="primary" size="small">{{ currentResult.label }}</el-tag></el-descriptions-item>
            <el-descriptions-item label="置信度">{{ (currentResult.confidence * 100).toFixed(2) }}%</el-descriptions-item>
            <el-descriptions-item label="边界框">[{{ currentResult.bbox.map(v => v.toFixed(2)).join(', ') }}]</el-descriptions-item>
          </el-descriptions>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { projectAPI } from '@/api'
import { ElMessage } from 'element-plus'

const props = defineProps({ project: { type: Object, required: true } })
const loading = ref(false); const results = ref([]); const filterLabel = ref(''); const minConfidence = ref(0); const currentPage = ref(1); const pageSize = ref(20); const previewVisible = ref(false); const currentResult = ref(null); const previewCanvasRef = ref(null)
const availableLabels = computed(() => { const labels = new Set(); results.value.forEach(r => labels.add(r.label)); return Array.from(labels) })
const filteredResults = computed(() => { let filtered = results.value; if (filterLabel.value) filtered = filtered.filter(r => r.label === filterLabel.value); if (minConfidence.value > 0) filtered = filtered.filter(r => r.confidence >= minConfidence.value); return filtered })
const paginatedResults = computed(() => { const start = (currentPage.value - 1) * pageSize.value; return filteredResults.value.slice(start, start + pageSize.value) })
const totalPages = computed(() => Math.ceil(filteredResults.value.length / pageSize.value))
const loadResults = async () => {
  if (!props.project || !props.project.id || props.project.id === 'null') return
  try { loading.value = true; const size = Math.max(props.project.totalImages || 0, 5000); const response = await projectAPI.getProjectImages(props.project.id, { page: 1, size }); const images = response.data.images || []; results.value = []; images.forEach(img => { if (img.detections && img.detections.length > 0) { img.detections.forEach(det => { results.value.push({ imageName: img.name, imagePath: img.path, imageUrl: img.url || `/api/v1/files/${img.path}`, label: det.label, confidence: det.confidence, bbox: det.bbox }) }) } }); currentPage.value = 1 } catch (error) { ElMessage.error('加载检测结果失败') } finally { loading.value = false }
}
const normalizeBox = (bbox) => {
  if (bbox && typeof bbox === 'object' && !Array.isArray(bbox)) {
    const x = Number(bbox.x ?? bbox.x1)
    const y = Number(bbox.y ?? bbox.y1)
    const width = Number(bbox.width ?? (bbox.x2 - bbox.x1))
    const height = Number(bbox.height ?? (bbox.y2 - bbox.y1))
    return [x, y, width, height].some(v => Number.isNaN(v)) ? null : { x, y, width, height }
  }
  if (!Array.isArray(bbox) || bbox.length < 4) return null
  const [a, b, c, d] = bbox.map(Number)
  if ([a, b, c, d].some(v => Number.isNaN(v))) return null
  return { x: a, y: b, width: c, height: d }
}
const drawPreview = async () => {
  await nextTick()
  const canvas = previewCanvasRef.value
  const result = currentResult.value
  if (!canvas || !result) return
  const ctx = canvas.getContext('2d')
  const img = new Image()
  img.onload = () => {
    canvas.width = img.naturalWidth || img.width
    canvas.height = img.naturalHeight || img.height
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height)

    const box = normalizeBox(result.bbox)
    if (!box) return

    ctx.strokeStyle = '#E24B4A'
    ctx.lineWidth = Math.max(3, Math.round(canvas.width / 500))
    ctx.strokeRect(box.x, box.y, box.width, box.height)

    const labelText = `${result.label} ${(result.confidence * 100).toFixed(1)}%`
    ctx.font = `bold ${Math.max(16, Math.round(canvas.width / 45))}px Arial`
    const textWidth = ctx.measureText(labelText).width
    const labelHeight = Math.max(26, Math.round(canvas.width / 30))
    const labelY = Math.max(0, box.y - labelHeight)
    ctx.fillStyle = '#E24B4A'
    ctx.fillRect(box.x, labelY, textWidth + 14, labelHeight)
    ctx.fillStyle = '#FFFFFF'
    ctx.fillText(labelText, box.x + 7, labelY + labelHeight - 8)
  }
  img.onerror = () => ElMessage.error('图片加载失败，无法绘制预览框')
  img.src = result.imageUrl
}
const previewImage = async (result) => {
  currentResult.value = result
  previewVisible.value = true
  await drawPreview()
}
onMounted(() => { loadResults() })
</script>

<style scoped>
.detection-results { }
.toolbar { margin-bottom: 12px; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.toolbar-label { margin-left: 8px; font-size: 13px; color: var(--gray-500); }
.result-summary { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; color: var(--gray-500); font-size: 13px; flex-wrap: wrap; }
.empty-state { padding: 48px 0; }
.preview-container { display: flex; flex-direction: column; gap: 16px; }
.preview-canvas-wrap { height: min(72vh, 720px); overflow: hidden; background: var(--gray-50); border: 1px solid var(--gray-200); border-radius: 6px; display: flex; align-items: center; justify-content: center; }
.preview-canvas { max-width: 100%; max-height: 100%; width: auto; height: auto; display: block; object-fit: contain; }
.preview-info { margin-top: 16px; }
</style>
