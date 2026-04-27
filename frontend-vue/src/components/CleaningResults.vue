<template>
  <div class="cleaning-results">
    <div class="toolbar">
      <el-button @click="loadResults" :loading="loading"><el-icon><Refresh /></el-icon>刷新</el-button>
      <el-select v-model="filterAction" placeholder="筛选操作" clearable style="width: 150px; margin-left: 10px;" @change="loadResults">
        <el-option label="保留" value="keep" /><el-option label="删除" value="remove" />
      </el-select>
    </div>
    <div class="result-summary">
      <el-tag :type="hasVlmCleaning ? 'success' : 'warning'">{{ hasVlmCleaning ? 'DINO + VLM' : 'DINO only' }}</el-tag>
      <el-tag type="info">Total {{ filteredResults.length }}</el-tag>
      <span>{{ hasVlmCleaning ? '当前展示 VLM 清洗后的最终结果。' : '当前项目没有 VLM 清洗记录，最终结果直接使用 DINO 检测框。' }}</span>
    </div>
    <el-table :data="paginatedResults" v-loading="loading" style="width: 100%">
      <el-table-column prop="imageName" label="图片名称" min-width="170" show-overflow-tooltip />
      <el-table-column prop="action" label="操作" width="100"><template #default="{ row }"><el-tag :type="row.action === 'keep' ? 'success' : 'danger'" size="small">{{ row.action === 'keep' ? '保留' : '删除' }}</el-tag></template></el-table-column>
      <el-table-column prop="reason" :label="hasVlmCleaning ? '原因' : '来源'" min-width="180" show-overflow-tooltip />
      <el-table-column label="预览" width="82"><template #default="{ row }"><el-button type="primary" link size="small" @click="previewImage(row)">预览</el-button></template></el-table-column>
    </el-table>
    <div v-if="results.length === 0" class="empty-state"><el-empty description="暂无清洗结果" /></div>
    <el-pagination v-if="totalPages > 1" v-model:current-page="currentPage" v-model:page-size="pageSize" :page-sizes="[20, 50, 100]" :total="filteredResults.length" layout="total, sizes, prev, pager, next, jumper" style="margin-top: 16px; display: flex; justify-content: flex-end;" />
    <el-dialog v-model="previewVisible" title="清洗结果预览" width="92vw">
      <div v-if="currentResult" class="preview-container">
        <div class="preview-visual">
          <canvas ref="previewCanvasRef" class="preview-canvas" />
          <div class="decision-badge" :class="currentResult.action">
            {{ currentResult.action === 'keep' ? '保留' : '删除' }}
          </div>
        </div>
        <div class="preview-info">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="图片名称">{{ currentResult.imageName }}</el-descriptions-item>
            <el-descriptions-item label="操作"><el-tag :type="currentResult.action === 'keep' ? 'success' : 'danger'" size="small">{{ currentResult.action === 'keep' ? '保留' : '删除' }}</el-tag></el-descriptions-item>
            <el-descriptions-item label="标签" :span="2"><el-tag v-for="label in displayLabels(currentResult)" :key="label" :type="currentResult.action === 'keep' ? 'success' : 'danger'" size="small" style="margin-right: 4px;">{{ label }}</el-tag></el-descriptions-item>
            <el-descriptions-item label="原因" :span="2">{{ currentResult.reason }}</el-descriptions-item>
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
const loading = ref(false); const results = ref([]); const filterAction = ref(''); const currentPage = ref(1); const pageSize = ref(20); const previewVisible = ref(false); const currentResult = ref(null); const previewCanvasRef = ref(null)
const hasVlmCleaning = computed(() => results.value.some(r => r.source === 'vlm'))
const filteredResults = computed(() => { let filtered = results.value; if (filterAction.value) filtered = filtered.filter(r => r.action === filterAction.value); return filtered })
const paginatedResults = computed(() => { const start = (currentPage.value - 1) * pageSize.value; return filteredResults.value.slice(start, start + pageSize.value) })
const totalPages = computed(() => Math.ceil(filteredResults.value.length / pageSize.value))
const loadResults = async () => {
  if (!props.project || !props.project.id || props.project.id === 'null') return
  try { loading.value = true; const size = Math.max(props.project.totalImages || 0, 5000); const response = await projectAPI.getProjectImages(props.project.id, { page: 1, size }); const images = response.data.images || []; const nextResults = []; let foundVlm = false; images.forEach(img => { if (img.cleaningResults && img.cleaningResults.length > 0) { foundVlm = true; img.cleaningResults.forEach(clean => { nextResults.push({ imageName: img.name, imagePath: img.path, imageUrl: img.url || `/api/v1/files/${img.path}`, originalLabels: clean.originalLabels || [], cleanedLabels: clean.cleanedLabels || [], removedLabels: clean.removedLabels || [], action: clean.cleanedLabels && clean.cleanedLabels.length > 0 ? 'keep' : 'remove', reason: clean.reason || '', bbox: clean.bbox, confidence: clean.confidence, source: 'vlm' }) }) } }); if (!foundVlm) { images.forEach(img => { if (img.detections && img.detections.length > 0) { img.detections.forEach(det => { nextResults.push({ imageName: img.name, imagePath: img.path, imageUrl: img.url || `/api/v1/files/${img.path}`, originalLabels: [det.label], cleanedLabels: [det.label], removedLabels: [], action: 'keep', reason: 'DINO detection retained without VLM cleaning', bbox: det.bbox, confidence: det.confidence, source: 'dino' }) }) } }) } results.value = nextResults; currentPage.value = 1 } catch (error) { ElMessage.error('加载清洗结果失败') } finally { loading.value = false }
}
const displayLabels = (result) => result.action === 'keep' ? result.cleanedLabels : result.removedLabels
const normalizeBox = (bbox) => {
  if (bbox && typeof bbox === 'object' && !Array.isArray(bbox)) {
    const x = Number(bbox.x ?? bbox.x1)
    const y = Number(bbox.y ?? bbox.y1)
    const width = Number(bbox.width ?? (bbox.x2 - bbox.x1))
    const height = Number(bbox.height ?? (bbox.y2 - bbox.y1))
    return [x, y, width, height].some(v => Number.isNaN(v)) ? null : { x, y, width, height }
  }
  if (!Array.isArray(bbox) || bbox.length < 4) return null
  const [x, y, width, height] = bbox.map(Number)
  return [x, y, width, height].some(v => Number.isNaN(v)) ? null : { x, y, width, height }
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
    const color = result.action === 'keep' ? '#16a34a' : '#dc2626'
    ctx.strokeStyle = color
    ctx.lineWidth = Math.max(3, Math.round(canvas.width / 500))
    ctx.strokeRect(box.x, box.y, box.width, box.height)
  }
  img.onerror = () => ElMessage.error('图片加载失败，无法绘制预览')
  img.src = result.imageUrl
}
const previewImage = async (result) => { currentResult.value = result; previewVisible.value = true; await drawPreview() }
onMounted(() => { loadResults() })
</script>

<style scoped>
.cleaning-results { }
.toolbar { margin-bottom: 12px; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.result-summary { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; color: var(--gray-500); font-size: 13px; flex-wrap: wrap; }
.empty-state { padding: 48px 0; }
.preview-container { display: flex; flex-direction: column; gap: 16px; }
.preview-visual { position: relative; height: min(72vh, 720px); overflow: hidden; background: var(--gray-50); border: 1px solid var(--gray-200); border-radius: 6px; display: flex; align-items: center; justify-content: center; }
.preview-canvas { max-width: 100%; max-height: 100%; width: auto; height: auto; display: block; object-fit: contain; }
.decision-badge { position: absolute; top: 12px; right: 12px; padding: 6px 12px; border-radius: 6px; color: #fff; font-weight: 600; font-size: 13px; }
.decision-badge.keep { background: #16a34a; }
.decision-badge.remove { background: #dc2626; }
.preview-info { margin-top: 16px; }
</style>
