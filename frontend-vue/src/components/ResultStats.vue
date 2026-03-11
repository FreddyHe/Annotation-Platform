<template>
  <div class="result-stats">
    <el-alert
      title="统计分析"
      type="info"
      :closable="false"
      style="margin-bottom: 20px;"
    >
      <template #default>
        查看项目数据的统计分析结果
      </template>
    </el-alert>

    <el-row :gutter="20" style="margin-bottom: 20px;">
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="总图片数" :value="stats.totalImages">
            <template #prefix>
              <el-icon><Picture /></el-icon>
            </template>
          </el-statistic>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="已处理" :value="stats.processedImages">
            <template #prefix>
              <el-icon><CircleCheck /></el-icon>
            </template>
          </el-statistic>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="总检测数" :value="stats.totalDetections">
            <template #prefix>
              <el-icon><Location /></el-icon>
            </template>
          </el-statistic>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <el-statistic title="平均置信度" :value="stats.avgConfidence" :precision="2">
            <template #suffix>
              <span>%</span>
            </template>
            <template #prefix>
              <el-icon><TrendCharts /></el-icon>
            </template>
          </el-statistic>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>标签分布</span>
            </div>
          </template>
          <div class="chart-container">
            <div
              v-for="(item, index) in labelDistribution"
              :key="item.label"
              class="chart-item"
            >
              <div class="chart-label">{{ item.label }}</div>
              <el-progress
                :percentage="item.percentage"
                :color="getChartColor(index)"
                :stroke-width="20"
              />
              <div class="chart-value">{{ item.count }}</div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>置信度分布</span>
            </div>
          </template>
          <div class="chart-container">
            <div
              v-for="(item, index) in confidenceDistribution"
              :key="item.range"
              class="chart-item"
            >
              <div class="chart-label">{{ item.range }}</div>
              <el-progress
                :percentage="item.percentage"
                :color="getChartColor(index)"
                :stroke-width="20"
              />
              <div class="chart-value">{{ item.count }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col :span="24">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>处理进度</span>
            </div>
          </template>
          <el-progress
            :percentage="processingProgress"
            :status="processingProgress === 100 ? 'success' : undefined"
            :stroke-width="30"
          />
          <div class="progress-info">
            <span>已处理: {{ stats.processedImages }} / {{ stats.totalImages }}</span>
            <span>进度: {{ processingProgress }}%</span>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { projectAPI } from '@/api'
import { ElMessage } from 'element-plus'

const props = defineProps({
  project: {
    type: Object,
    required: true
  }
})

const stats = ref({
  totalImages: 0,
  processedImages: 0,
  totalDetections: 0,
  avgConfidence: 0
})

const labelDistribution = ref([])
const confidenceDistribution = ref([])

const processingProgress = computed(() => {
  if (stats.value.totalImages === 0) return 0
  return Math.round((stats.value.processedImages / stats.value.totalImages) * 100)
})

const getChartColor = (index) => {
  const colors = [
    '#409eff',
    '#67c23a',
    '#e6a23c',
    '#f56c6c',
    '#909399',
    '#c0c4cc'
  ]
  return colors[index % colors.length]
}

const loadStats = async () => {
  if (!props.project || !props.project.id || props.project.id === 'null') {
    return
  }
  
  try {
    const response = await projectAPI.getProjectStats(props.project.id)
    stats.value = response.data
    
    calculateLabelDistribution()
    calculateConfidenceDistribution()
  } catch (error) {
    ElMessage.error('加载统计信息失败')
  }
}

const calculateLabelDistribution = () => {
  const labelCounts = {}
  const images = stats.value.images || []
  
  images.forEach(img => {
    if (img.detections && img.detections.length > 0) {
      img.detections.forEach(det => {
        const label = det.label
        if (!labelCounts[label]) {
          labelCounts[label] = 0
        }
        labelCounts[label]++
      })
    }
  })
  
  const total = Object.values(labelCounts).reduce((sum, count) => sum + count, 0)
  
  labelDistribution.value = Object.entries(labelCounts)
    .map(([label, count]) => ({
      label,
      count,
      percentage: total > 0 ? Math.round((count / total) * 100) : 0
    }))
    .sort((a, b) => b.count - a.count)
}

const calculateConfidenceDistribution = () => {
  const ranges = [
    { range: '0-20%', min: 0, max: 0.2, count: 0 },
    { range: '20-40%', min: 0.2, max: 0.4, count: 0 },
    { range: '40-60%', min: 0.4, max: 0.6, count: 0 },
    { range: '60-80%', min: 0.6, max: 0.8, count: 0 },
    { range: '80-100%', min: 0.8, max: 1.0, count: 0 }
  ]
  
  const images = stats.value.images || []
  
  images.forEach(img => {
    if (img.detections && img.detections.length > 0) {
      img.detections.forEach(det => {
        const confidence = det.confidence
        ranges.forEach(range => {
          if (confidence >= range.min && confidence < range.max) {
            range.count++
          }
        })
      })
    }
  })
  
  const total = ranges.reduce((sum, range) => sum + range.count, 0)
  
  confidenceDistribution.value = ranges.map(range => ({
    range: range.range,
    count: range.count,
    percentage: total > 0 ? Math.round((range.count / total) * 100) : 0
  }))
}

onMounted(() => {
  loadStats()
})
</script>

<style scoped>
.result-stats {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chart-container {
  padding: 20px 0;
}

.chart-item {
  display: flex;
  align-items: center;
  margin-bottom: 20px;
}

.chart-label {
  width: 100px;
  font-weight: 500;
  color: #606266;
}

.chart-value {
  width: 60px;
  text-align: right;
  font-weight: 600;
  color: #303133;
}

.progress-info {
  display: flex;
  justify-content: space-between;
  margin-top: 20px;
  font-size: 14px;
  color: #606266;
}
</style>
