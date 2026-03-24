<template>
  <div class="data-manager">
    <p class="section-desc">上传图片数据到项目，支持大文件分块上传、ZIP 压缩包、视频抽帧</p>

    <div class="stat-grid">
      <el-card shadow="never" class="mini-stat">
        <el-statistic title="总图片数" :value="stats.totalImages">
          <template #prefix><el-icon><Picture /></el-icon></template>
        </el-statistic>
      </el-card>
      <el-card shadow="never" class="mini-stat">
        <el-statistic title="已处理" :value="stats.processedImages">
          <template #prefix><el-icon><CircleCheck /></el-icon></template>
        </el-statistic>
      </el-card>
      <el-card shadow="never" class="mini-stat">
        <el-statistic title="未处理" :value="stats.totalImages - stats.processedImages">
          <template #prefix><el-icon><Clock /></el-icon></template>
        </el-statistic>
      </el-card>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="文件上传" name="upload">
        <FileUpload :project-id="project.id" @uploaded="handleUploaded" />
      </el-tab-pane>
      <el-tab-pane label="图片列表" name="images">
        <ImageList :project-id="project.id" />
      </el-tab-pane>
      <el-tab-pane label="视频抽帧" name="video">
        <VideoExtract :project-id="project.id" @extracted="handleExtracted" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { projectAPI } from '@/api'
import FileUpload from '@/components/FileUpload.vue'
import ImageList from '@/components/ImageList.vue'
import VideoExtract from '@/components/VideoExtract.vue'

const props = defineProps({ project: { type: Object, required: true } })
const emit = defineEmits(['refresh'])
const activeTab = ref('upload')
const stats = ref({ totalImages: 0, processedImages: 0 })

const loadStats = async () => {
  if (!props.project || !props.project.id || props.project.id === 'null') return
  try { const response = await projectAPI.getProjectStats(props.project.id); stats.value = response.data } catch (error) { console.error('加载统计信息失败', error) }
}
const handleUploaded = () => { loadStats(); emit('refresh') }
const handleExtracted = () => { loadStats(); emit('refresh') }
onMounted(() => { loadStats() })
</script>

<style scoped>
.data-manager { }
.section-desc { color: var(--gray-500); font-size: 13px; margin-bottom: 20px; }
.stat-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 20px; }
.mini-stat { background: var(--gray-50) !important; border: none !important; }
</style>
