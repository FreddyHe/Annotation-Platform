<template>
  <div class="result-export">
    <p class="section-desc">导出最终标注：已审核图片使用人工审核结果，未审核图片使用自动标注结果。</p>
    
    <div class="export-card">
      <div class="format-selector">
        <el-radio-group v-model="selectedFormat">
          <el-radio-button value="coco">COCO JSON</el-radio-button>
          <el-radio-button value="yolo">YOLO TXT</el-radio-button>
          <el-radio-button value="voc">VOC XML</el-radio-button>
          <el-radio-button value="csv">CSV</el-radio-button>
          <el-radio-button value="json">JSON</el-radio-button>
        </el-radio-group>
      </div>

      <div class="export-action">
        <el-button 
          type="primary" 
          size="large" 
          @click="handleExport" 
          :loading="exporting"
          :disabled="!selectedFormat">
          <el-icon><Download /></el-icon>
          导出标注结果
        </el-button>
      </div>
    </div>

    <div v-if="exporting" class="export-progress">
      <el-progress :percentage="exportProgress" :status="exportProgress === 100 ? 'success' : undefined" />
      <div class="progress-info">
        <span>{{ currentStatus }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { projectAPI } from '@/api'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'

const props = defineProps({ 
  project: { type: Object, required: true } 
})

const selectedFormat = ref('coco')
const exporting = ref(false)
const exportProgress = ref(0)
const currentStatus = ref('')

const handleExport = async () => {
  if (!props.project || !props.project.id || !(props.project.lsProjectId || props.project.labelStudioProjectId)) {
    ElMessage.warning('项目尚未同步到星目智能标注')
    return
  }

  try {
    exporting.value = true
    exportProgress.value = 0
    currentStatus.value = '正在合并人工审核与自动标注数据...'
    
    const requestData = {
      projectId: props.project.id,
      format: selectedFormat.value
    }
    
    exportProgress.value = 30
    currentStatus.value = '正在生成导出文件...'
    
    const response = await projectAPI.exportResults(requestData)
    
    exportProgress.value = 80
    currentStatus.value = '正在下载文件...'
    
    if (response.data && response.data.downloadUrl) {
      const formatExtensions = {
        'coco': { ext: 'json', mime: 'application/json' },
        'yolo': { ext: 'txt', mime: 'text/plain' },
        'voc': { ext: 'xml', mime: 'application/xml' },
        'csv': { ext: 'csv', mime: 'text/csv' },
        'json': { ext: 'json', mime: 'application/json' }
      }
      
      const formatInfo = formatExtensions[selectedFormat.value] || { ext: 'txt', mime: 'text/plain' }
      const downloadUrl = response.data.downloadUrl
      const link = document.createElement('a')
      if (downloadUrl.startsWith('data:')) {
        const encodedData = downloadUrl.split(',')[1] || ''
        const decodedData = decodeURIComponent(encodedData.replace(/\+/g, ' '))
        const blob = new Blob([decodedData], { type: formatInfo.mime })
        link.href = window.URL.createObjectURL(blob)
      } else {
        link.href = downloadUrl
      }
      link.download = response.data.filename || `${props.project.name}_${selectedFormat.value}_export.${formatInfo.ext}`
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      if (downloadUrl.startsWith('data:')) {
        window.URL.revokeObjectURL(link.href)
      }
      
      exportProgress.value = 100
      currentStatus.value = '导出完成！'
      
      setTimeout(() => {
        exporting.value = false
        exportProgress.value = 0
        currentStatus.value = ''
      }, 2000)
      
      ElMessage.success('导出成功！')
    } else {
      throw new Error('未获取到下载链接')
    }
  } catch (error) {
    ElMessage.error('导出失败：' + (error.message || '未知错误'))
    exporting.value = false
    exportProgress.value = 0
    currentStatus.value = ''
  }
}
</script>

<style scoped>
.result-export { max-width: none; }

.section-desc {
  color: var(--gray-500);
  font-size: 13px;
  margin-bottom: 12px;
}

.export-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.format-selector {
  display: flex;
  justify-content: flex-start;
}

.export-action {
  display: flex;
  justify-content: flex-start;
}

.export-progress {
  margin-top: 24px;
  padding: 20px;
  background: var(--gray-50);
  border-radius: 8px;
}

.progress-info {
  display: flex;
  justify-content: center;
  margin-top: 12px;
  font-size: 13px;
  color: var(--gray-600);
  font-weight: 500;
}
</style>
