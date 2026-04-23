<template>
  <div class="result-export">
    <p class="section-desc">导出 Label Studio 中已审核的标注结果为多种格式，用于模型训练或其他用途</p>
    
    <el-card class="export-card">
      <template #header>
        <span class="card-title">导出格式</span>
      </template>
      
      <div class="format-selector">
        <el-radio-group v-model="selectedFormat" size="large">
          <el-radio-button label="coco">COCO JSON</el-radio-button>
          <el-radio-button label="yolo">YOLO TXT</el-radio-button>
          <el-radio-button label="voc">VOC XML</el-radio-button>
          <el-radio-button label="csv">CSV</el-radio-button>
          <el-radio-button label="json">JSON</el-radio-button>
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
    </el-card>

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
  if (!props.project || !props.project.id || !props.project.labelStudioProjectId) {
    ElMessage.warning('项目尚未同步到 Label Studio')
    return
  }

  try {
    exporting.value = true
    exportProgress.value = 0
    currentStatus.value = '正在从 Label Studio 获取标注数据...'
    
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
      // 从 data URL 中提取实际数据
      const dataUrl = response.data.downloadUrl
      const base64Data = dataUrl.split(',')[1]
      const decodedData = decodeURIComponent(base64Data)
      
      // 根据格式确定文件扩展名和 MIME 类型
      const formatExtensions = {
        'coco': { ext: 'json', mime: 'application/json' },
        'yolo': { ext: 'txt', mime: 'text/plain' },
        'voc': { ext: 'xml', mime: 'application/xml' },
        'csv': { ext: 'csv', mime: 'text/csv' },
        'json': { ext: 'json', mime: 'application/json' }
      }
      
      const formatInfo = formatExtensions[selectedFormat.value] || { ext: 'txt', mime: 'text/plain' }
      
      // 创建 Blob 并下载
      const blob = new Blob([decodedData], { type: formatInfo.mime })
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `${props.project.name}_${selectedFormat.value}_export.${formatInfo.ext}`
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      window.URL.revokeObjectURL(url)
      
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
.result-export {
  max-width: 800px;
}

.section-desc {
  color: var(--gray-500);
  font-size: 13px;
  margin-bottom: 20px;
}

.export-card {
  margin-bottom: 24px;
}

.card-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--gray-900);
}

.format-selector {
  display: flex;
  justify-content: center;
  padding: 24px 0;
}

.export-action {
  display: flex;
  justify-content: center;
  padding-top: 24px;
  border-top: 1px solid var(--gray-200);
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
