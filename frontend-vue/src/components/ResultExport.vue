<template>
  <div class="result-export">
    <el-alert
      title="导出结果"
      type="info"
      :closable="false"
      style="margin-bottom: 20px;"
    >
      <template #default>
        导出标注结果为多种格式，用于模型训练或其他用途
      </template>
    </el-alert>

    <el-form :model="form" label-width="120px">
      <el-form-item label="导出格式">
        <el-radio-group v-model="form.format">
          <el-radio label="coco">COCO JSON</el-radio>
          <el-radio label="yolo">YOLO TXT</el-radio>
          <el-radio label="voc">VOC XML</el-radio>
          <el-radio label="csv">CSV</el-radio>
          <el-radio label="json">JSON</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="导出范围">
        <el-radio-group v-model="form.scope">
          <el-radio label="all">全部数据</el-radio>
          <el-radio label="processed">已处理数据</el-radio>
          <el-radio label="selected">选择标签</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="form.scope === 'selected'" label="选择标签">
        <el-select
          v-model="form.labels"
          multiple
          filterable
          placeholder="请选择要导出的标签"
          style="width: 100%;"
        >
          <el-option
            v-for="label in availableLabels"
            :key="label"
            :label="label"
            :value="label"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="导出选项">
        <el-checkbox-group v-model="form.options">
          <el-checkbox label="includeImages">包含图片</el-checkbox>
          <el-checkbox label="includeOriginal">包含原始标注</el-checkbox>
          <el-checkbox label="includeCleaning">包含清洗结果</el-checkbox>
          <el-checkbox label="splitDataset">划分数据集</el-checkbox>
        </el-checkbox-group>
      </el-form-item>

      <el-form-item v-if="form.options.includes('splitDataset')" label="数据集划分">
        <el-row :gutter="20">
          <el-col :span="8">
            <el-input-number
              v-model="form.trainRatio"
              :min="0"
              :max="100"
              :step="5"
              style="width: 100%;"
            />
            <span style="margin-left: 10px;">训练集 %</span>
          </el-col>
          <el-col :span="8">
            <el-input-number
              v-model="form.valRatio"
              :min="0"
              :max="100"
              :step="5"
              style="width: 100%;"
            />
            <span style="margin-left: 10px;">验证集 %</span>
          </el-col>
          <el-col :span="8">
            <el-input-number
              v-model="form.testRatio"
              :min="0"
              :max="100"
              :step="5"
              style="width: 100%;"
            />
            <span style="margin-left: 10px;">测试集 %</span>
          </el-col>
        </el-row>
      </el-form-item>

      <el-form-item label="最小置信度">
        <el-slider
          v-model="form.minConfidence"
          :min="0"
          :max="1"
          :step="0.05"
          :format-tooltip="formatThreshold"
          style="width: 300px;"
        />
        <span style="margin-left: 10px;">{{ form.minConfidence }}</span>
      </el-form-item>

      <el-form-item>
        <el-button type="primary" size="large" @click="handleExport" :loading="exporting">
          <el-icon><Download /></el-icon>
          开始导出
        </el-button>
      </el-form-item>
    </el-form>

    <div v-if="exporting" class="export-progress">
      <el-progress :percentage="exportProgress" />
      <div class="progress-info">
        <span>{{ currentStatus }}</span>
        <span>{{ exportedItems }} / {{ totalItems }} 项</span>
      </div>
    </div>

    <div v-if="exportHistory.length > 0" class="export-history">
      <el-divider>导出历史</el-divider>
      <el-table :data="exportHistory" style="width: 100%">
        <el-table-column prop="format" label="格式" width="100" />
        <el-table-column prop="scope" label="范围" width="120" />
        <el-table-column prop="itemCount" label="项目数" width="100" />
        <el-table-column prop="fileSize" label="文件大小" width="120">
          <template #default="{ row }">
            {{ formatFileSize(row.fileSize) }}
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="导出时间" width="180" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="downloadExport(row)">
              下载
            </el-button>
            <el-button type="danger" link size="small" @click="deleteExport(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
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

const form = ref({
  format: 'coco',
  scope: 'all',
  labels: [],
  options: ['includeOriginal', 'includeCleaning'],
  trainRatio: 70,
  valRatio: 20,
  testRatio: 10,
  minConfidence: 0.5
})

const availableLabels = ref([])
const exporting = ref(false)
const exportProgress = ref(0)
const currentStatus = ref('')
const exportedItems = ref(0)
const totalItems = ref(0)
const exportHistory = ref([])

const formatThreshold = (value) => {
  return (value * 100).toFixed(0) + '%'
}

const formatFileSize = (bytes) => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i]
}

const loadAvailableLabels = () => {
  const labels = props.project.labels || {}
  availableLabels.value = Object.keys(labels)
}

const handleExport = async () => {
  if (!props.project || !props.project.id || props.project.id === 'null') {
    return
  }
  
  try {
    exporting.value = true
    exportProgress.value = 0
    currentStatus.value = '正在准备导出...'
    exportedItems.value = 0
    totalItems.value = 0

    const requestData = {
      projectId: props.project.id,
      format: form.value.format,
      scope: form.value.scope,
      options: form.value.options,
      minConfidence: form.value.minConfidence
    }

    if (form.value.scope === 'selected') {
      requestData.labels = form.value.labels
    }

    if (form.value.options.includes('splitDataset')) {
      requestData.splitRatio = {
        train: form.value.trainRatio / 100,
        val: form.value.valRatio / 100,
        test: form.value.testRatio / 100
      }
    }

    const response = await projectAPI.exportResults(requestData)
    
    exportProgress.value = 100
    currentStatus.value = '导出完成'
    
    exportHistory.value.unshift({
      id: response.data.exportId,
      format: form.value.format,
      scope: form.value.scope,
      itemCount: response.data.itemCount,
      fileSize: response.data.fileSize,
      downloadUrl: response.data.downloadUrl,
      createdAt: new Date().toLocaleString()
    })

    ElMessage.success('导出成功')
  } catch (error) {
    ElMessage.error('导出失败：' + (error.message || '未知错误'))
  } finally {
    exporting.value = false
  }
}

const downloadExport = async (exportItem) => {
  try {
    const link = document.createElement('a')
    link.href = exportItem.downloadUrl
    link.download = `${props.project.name}_${exportItem.format}_export.zip`
    link.click()
    ElMessage.success('下载成功')
  } catch (error) {
    ElMessage.error('下载失败')
  }
}

const deleteExport = async (exportItem) => {
  try {
    await projectAPI.deleteExport(exportItem.id)
    exportHistory.value = exportHistory.value.filter(e => e.id !== exportItem.id)
    ElMessage.success('删除成功')
  } catch (error) {
    ElMessage.error('删除失败')
  }
}

onMounted(() => {
  loadAvailableLabels()
})
</script>

<style scoped>
.result-export {
  padding: 20px;
}

.export-progress {
  margin-top: 30px;
  padding: 20px;
  background: #f5f7fa;
  border-radius: 8px;
}

.progress-info {
  display: flex;
  justify-content: space-between;
  margin-top: 10px;
  font-size: 14px;
  color: #606266;
}

.export-history {
  margin-top: 30px;
}
</style>
