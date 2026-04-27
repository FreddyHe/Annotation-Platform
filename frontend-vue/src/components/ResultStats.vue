<template>
  <div class="result-stats">
    <div class="stat-grid">
      <el-card shadow="never" class="mini-stat"><el-statistic title="总图片数" :value="stats.totalImages"><template #prefix><el-icon><Picture /></el-icon></template></el-statistic></el-card>
      <el-card shadow="never" class="mini-stat"><el-statistic title="已处理" :value="stats.processedImages"><template #prefix><el-icon><CircleCheck /></el-icon></template></el-statistic></el-card>
      <el-card shadow="never" class="mini-stat"><el-statistic :title="stats.hasVlmCleaning ? '最终结果数' : '总检测数'" :value="stats.hasVlmCleaning ? stats.totalFinalResults : stats.totalDetections"><template #prefix><el-icon><Location /></el-icon></template></el-statistic></el-card>
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
const stats = ref({ totalImages: 0, processedImages: 0, totalDetections: 0, totalFinalResults: 0, avgConfidence: 0, hasVlmCleaning: false })
const labelDistribution = ref([]); const confidenceDistribution = ref([])
const processingProgress = computed(() => { if (stats.value.totalImages === 0) return 0; return Math.round((stats.value.processedImages / stats.value.totalImages) * 100) })
const getChartColor = (index) => { const colors = ['#534AB7', '#1D9E75', '#EF9F27', '#E24B4A', '#78716c', '#85B7EB']; return colors[index % colors.length] }
const loadStats = async () => {
  if (!props.project || !props.project.id || props.project.id === 'null') return
  try { const response = await projectAPI.getProjectStats(props.project.id); stats.value = response.data; calculateLabelDistribution(); calculateConfidenceDistribution() } catch (error) { ElMessage.error('加载统计信息失败') }
}
const calculateLabelDistribution = () => { const items = stats.value.labelDistribution || []; const total = items.reduce((sum, item) => sum + Number(item.count || 0), 0); labelDistribution.value = items.map(item => ({ label: item.label, count: item.count, percentage: total > 0 ? Math.round((item.count / total) * 100) : 0 })).sort((a, b) => b.count - a.count) }
const calculateConfidenceDistribution = () => { const items = stats.value.confidenceDistribution || []; const total = items.reduce((sum, item) => sum + Number(item.count || 0), 0); confidenceDistribution.value = items.map(item => ({ range: item.range, count: item.count, percentage: total > 0 ? Math.round((item.count / total) * 100) : 0 })) }
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
