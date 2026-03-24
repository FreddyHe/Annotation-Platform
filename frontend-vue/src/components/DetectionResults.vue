<template>
  <div class="detection-results">
    <div class="toolbar">
      <el-button @click="loadResults" :loading="loading"><el-icon><Refresh /></el-icon>刷新</el-button>
      <el-select v-model="filterLabel" placeholder="筛选标签" clearable style="width: 150px; margin-left: 10px;" @change="loadResults">
        <el-option v-for="label in availableLabels" :key="label" :label="label" :value="label" />
      </el-select>
      <el-input-number v-model="minConfidence" :min="0" :max="1" :step="0.1" :precision="2" style="width: 150px; margin-left: 10px;" @change="loadResults" />
      <span class="toolbar-label">最小置信度</span>
    </div>
    <el-table :data="filteredResults" v-loading="loading" style="width: 100%">
      <el-table-column prop="imageName" label="图片名称" width="200" />
      <el-table-column prop="label" label="标签" width="150"><template #default="{ row }"><el-tag type="primary" size="small">{{ row.label }}</el-tag></template></el-table-column>
      <el-table-column prop="confidence" label="置信度" width="120"><template #default="{ row }"><el-progress :percentage="Math.round(row.confidence * 100)" :stroke-width="8" /></template></el-table-column>
      <el-table-column prop="bbox" label="边界框" width="300"><template #default="{ row }"><span>[{{ row.bbox.map(v => v.toFixed(2)).join(', ') }}]</span></template></el-table-column>
      <el-table-column label="预览" width="100"><template #default="{ row }"><el-button type="primary" link size="small" @click="previewImage(row)">预览</el-button></template></el-table-column>
    </el-table>
    <div v-if="results.length === 0" class="empty-state"><el-empty description="暂无检测结果" /></div>
    <el-pagination v-if="totalPages > 1" v-model:current-page="currentPage" v-model:page-size="pageSize" :page-sizes="[20, 50, 100]" :total="filteredResults.length" layout="total, sizes, prev, pager, next, jumper" style="margin-top: 16px; display: flex; justify-content: flex-end;" />
    <el-dialog v-model="previewVisible" title="图片预览" width="80%">
      <div v-if="currentResult" class="preview-container">
        <el-image :src="currentResult.imageUrl" fit="contain" style="width: 100%; max-height: 600px;" />
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
import { ref, computed, onMounted } from 'vue'
import { projectAPI } from '@/api'
import { ElMessage } from 'element-plus'

const props = defineProps({ project: { type: Object, required: true } })
const loading = ref(false); const results = ref([]); const filterLabel = ref(''); const minConfidence = ref(0); const currentPage = ref(1); const pageSize = ref(20); const previewVisible = ref(false); const currentResult = ref(null)
const availableLabels = computed(() => { const labels = new Set(); results.value.forEach(r => labels.add(r.label)); return Array.from(labels) })
const filteredResults = computed(() => { let filtered = results.value; if (filterLabel.value) filtered = filtered.filter(r => r.label === filterLabel.value); if (minConfidence.value > 0) filtered = filtered.filter(r => r.confidence >= minConfidence.value); return filtered })
const totalPages = computed(() => Math.ceil(filteredResults.value.length / pageSize.value))
const loadResults = async () => {
  if (!props.project || !props.project.id || props.project.id === 'null') return
  try { loading.value = true; const response = await projectAPI.getProjectImages(props.project.id, { page: 1, pageSize: 1000 }); const images = response.data.images || []; results.value = []; images.forEach(img => { if (img.detections && img.detections.length > 0) { img.detections.forEach(det => { results.value.push({ imageName: img.name, imagePath: img.path, imageUrl: img.url, label: det.label, confidence: det.confidence, bbox: det.bbox }) }) } }) } catch (error) { ElMessage.error('加载检测结果失败') } finally { loading.value = false }
}
const previewImage = (result) => { currentResult.value = result; previewVisible.value = true }
onMounted(() => { loadResults() })
</script>

<style scoped>
.detection-results { }
.toolbar { margin-bottom: 16px; display: flex; align-items: center; }
.toolbar-label { margin-left: 8px; font-size: 13px; color: var(--gray-500); }
.empty-state { padding: 48px 0; }
.preview-container { display: flex; flex-direction: column; gap: 16px; }
.preview-info { margin-top: 16px; }
</style>
