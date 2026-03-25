<template>
  <div class="result-viewer">
    <p class="section-desc">查看算法检测结果、清洗后的标注数据和人工审核情况</p>
    <el-tabs v-model="activeTab">
      <el-tab-pane label="检测结果" name="detections"><DetectionResults :project="project" /></el-tab-pane>
      <el-tab-pane label="清洗结果" name="cleaning"><CleaningResults :project="project" /></el-tab-pane>
      <el-tab-pane label="审核结果" name="review"><ReviewResults :project="project" /></el-tab-pane>
      <el-tab-pane label="统计分析" name="stats"><ResultStats :project="project" /></el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import DetectionResults from '@/components/DetectionResults.vue'
import CleaningResults from '@/components/CleaningResults.vue'
import ReviewResults from '@/components/ReviewResults.vue'
import ResultStats from '@/components/ResultStats.vue'
const props = defineProps({ project: { type: Object, required: true } })
const activeTab = ref('detections')
</script>

<style scoped>
.result-viewer { }
.section-desc { color: var(--gray-500); font-size: 13px; margin-bottom: 16px; }
</style>
