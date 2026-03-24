<template>
  <div class="result-stats">
    <div class="stat-grid">
      <el-card shadow="never" class="mini-stat"><el-statistic title="总图片数" :value="stats.totalImages"><template #prefix><el-icon><Picture /></el-icon></template></el-statistic></el-card>
      <el-card shadow="never" class="mini-stat"><el-statistic title="已处理" :value="stats.processedImages"><template #prefix><el-icon><CircleCheck /></el-icon></template></el-statistic></el-card>
      <el-card shadow="never" class="mini-stat"><el-statistic title="总检测数" :value="stats.totalDetections"><template #prefix><el-icon><Location /></el-icon></template></el-statistic></el-card>
      <el-card shadow="never" class="mini-stat"><el-statistic title="平均置信度" :value="stats.avgConfidence" :precision="2"><template #suffix><span>%</span></template><template #prefix><el-icon><TrendCharts /></el-icon></template></el-statistic></el-card>
    </div>
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card>
          <template #header><span class="card-title">标签分布</span></template>
          <div class="chart-container">
            <div v-for="(item, index) in labelDistribution" :key="item.label" class="chart-item">
              <div class="chart-label">{{ item.label }}</div>
              <el-progress :percentage="item.percentage" :color="getChartColor(index)" :stroke-width="16" style="flex:1;" />
              <div class="chart-value">{{ item.count }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header><span class="card-title">置信度分布</span></template>
          <div class="chart-container">
            <div v-for="(item, index) in confidenceDistribution" :key="item.range" class="chart-item">
              <div class="chart-label">{{ item.range }}</div>
              <el-progress :percentage="item.percentage" :color="getChartColor(index)" :stroke-width="16" style="flex:1;" />
              <div class="chart-value">{{ item.count }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
    <el-card style="margin-top: 16px;">
      <template #header><span class="card-title">处理进度</span></template>
      <el-progress :percentage="processingProgress" :status="processingProgress === 100 ? 'success' : undefined" :stroke-width="24" />
      <div class="progress-info"><span>已处理: {{ stats.processedImages }} / {{ stats.totalImages }}</span><span>进度: {{ processingProgress }}%</span></div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { projectAPI } from '@/api'
import { ElMessage } from 'element-plus'

const props = defineProps({ project: { type: Object, required: true } })
const stats = ref({ totalImages: 0, processedImages: 0, totalDetections: 0, avgConfidence: 0 })
const labelDistribution = ref([]); const confidenceDistribution = ref([])
const processingProgress = computed(() => { if (stats.value.totalImages === 0) return 0; return Math.round((stats.value.processedImages / stats.value.totalImages) * 100) })
const getChartColor = (index) => { const colors = ['#534AB7', '#1D9E75', '#EF9F27', '#E24B4A', '#78716c', '#85B7EB']; return colors[index % colors.length] }
const loadStats = async () => {
  if (!props.project || !props.project.id || props.project.id === 'null') return
  try { const response = await projectAPI.getProjectStats(props.project.id); stats.value = response.data; calculateLabelDistribution(); calculateConfidenceDistribution() } catch (error) { ElMessage.error('加载统计信息失败') }
}
const calculateLabelDistribution = () => { const labelCounts = {}; const images = stats.value.images || []; images.forEach(img => { if (img.detections && img.detections.length > 0) { img.detections.forEach(det => { if (!labelCounts[det.label]) labelCounts[det.label] = 0; labelCounts[det.label]++ }) } }); const total = Object.values(labelCounts).reduce((sum, count) => sum + count, 0); labelDistribution.value = Object.entries(labelCounts).map(([label, count]) => ({ label, count, percentage: total > 0 ? Math.round((count / total) * 100) : 0 })).sort((a, b) => b.count - a.count) }
const calculateConfidenceDistribution = () => { const ranges = [{ range: '0-20%', min: 0, max: 0.2, count: 0 },{ range: '20-40%', min: 0.2, max: 0.4, count: 0 },{ range: '40-60%', min: 0.4, max: 0.6, count: 0 },{ range: '60-80%', min: 0.6, max: 0.8, count: 0 },{ range: '80-100%', min: 0.8, max: 1.0, count: 0 }]; const images = stats.value.images || []; images.forEach(img => { if (img.detections && img.detections.length > 0) { img.detections.forEach(det => { ranges.forEach(range => { if (det.confidence >= range.min && det.confidence < range.max) range.count++ }) }) } }); const total = ranges.reduce((sum, range) => sum + range.count, 0); confidenceDistribution.value = ranges.map(range => ({ range: range.range, count: range.count, percentage: total > 0 ? Math.round((range.count / total) * 100) : 0 })) }
onMounted(() => { loadStats() })
</script>

<style scoped>
.result-stats { }
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 20px; }
.mini-stat { background: var(--gray-50) !important; border: none !important; }
.card-title { font-size: 14px; font-weight: 500; color: var(--gray-900); }
.chart-container { padding: 8px 0; }
.chart-item { display: flex; align-items: center; margin-bottom: 14px; gap: 12px; }
.chart-label { width: 80px; font-size: 13px; font-weight: 500; color: var(--gray-600); flex-shrink: 0; }
.chart-value { width: 48px; text-align: right; font-weight: 600; color: var(--gray-900); font-size: 13px; flex-shrink: 0; }
.progress-info { display: flex; justify-content: space-between; margin-top: 16px; font-size: 13px; color: var(--gray-600); }
</style>
