<template>
  <div class="result-viewer">
    <p class="section-desc">查看算法检测结果、清洗后的标注数据、统计分析并导出最终标注</p>
    <div class="result-layout">
      <el-card shadow="never" class="result-panel stats-panel">
        <template #header><span class="panel-title">统计分析</span></template>
        <ResultStats :project="project" />
      </el-card>

      <div class="result-two-column">
        <el-card shadow="never" class="result-panel">
          <template #header><span class="panel-title">检测结果</span></template>
          <DetectionResults :project="project" />
        </el-card>

        <el-card shadow="never" class="result-panel">
          <template #header><span class="panel-title">清洗结果</span></template>
          <CleaningResults :project="project" />
        </el-card>
      </div>

      <el-card shadow="never" class="result-panel export-panel">
        <template #header><span class="panel-title">导出结果</span></template>
        <ResultExport :project="project" />
      </el-card>
    </div>
  </div>
</template>

<script setup>
import DetectionResults from '@/components/DetectionResults.vue'
import CleaningResults from '@/components/CleaningResults.vue'
import ResultStats from '@/components/ResultStats.vue'
import ResultExport from '@/components/ResultExport.vue'
const props = defineProps({ project: { type: Object, required: true } })
</script>

<style scoped>
.result-viewer { }
.section-desc { color: var(--gray-500); font-size: 13px; margin-bottom: 16px; }
.result-layout { display: grid; grid-template-columns: 1fr; gap: 14px; }
.result-two-column { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 14px; align-items: start; }
.result-panel { border-radius: 8px; }
.result-panel :deep(.el-card__header) { padding: 10px 14px; }
.result-panel :deep(.el-card__body) { padding: 14px; }
.panel-title { font-size: 14px; font-weight: 600; color: var(--gray-900); }
.export-panel :deep(.result-export) { max-width: none; }
@media (max-width: 1180px) {
  .result-two-column { grid-template-columns: 1fr; }
}
</style>
