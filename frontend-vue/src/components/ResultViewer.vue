<template>
  <div class="result-viewer">
    <el-alert
      title="结果查看"
      type="info"
      :closable="false"
      style="margin-bottom: 20px;"
    >
      <template #default>
        查看算法检测结果和清洗后的标注数据
      </template>
    </el-alert>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="🔍 检测结果" name="detections">
        <DetectionResults :project="project" />
      </el-tab-pane>

      <el-tab-pane label="🧹 清洗结果" name="cleaning">
        <CleaningResults :project="project" />
      </el-tab-pane>

      <el-tab-pane label="📊 统计分析" name="stats">
        <ResultStats :project="project" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import DetectionResults from '@/components/DetectionResults.vue'
import CleaningResults from '@/components/CleaningResults.vue'
import ResultStats from '@/components/ResultStats.vue'

const props = defineProps({
  project: {
    type: Object,
    required: true
  }
})

const activeTab = ref('detections')
</script>

<style scoped>
.result-viewer {
  padding: 20px;
}
</style>
