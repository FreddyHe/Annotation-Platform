<template>
  <div class="model-hub">
    <el-alert
      title="模型大厅与测试"
      type="info"
      :closable="false"
      style="margin-bottom: 20px;"
    >
      <template #default>
        查看和管理已训练的模型，并进行模型测试
      </template>
    </el-alert>

    <el-card class="table-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <el-icon><Trophy /></el-icon>
          <span>训练历史</span>
          <el-button type="primary" @click="loadTrainings">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </template>

      <el-table
        :data="trainings"
        v-loading="loading"
        stripe
        style="width: 100%"
      >
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="runName" label="运行名称" width="180" />
        <el-table-column prop="modelType" label="模型类型" width="120">
          <template #default="{ row }">
            <el-tag size="small">{{ row.modelType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="epochs" label="轮数" width="80" />
        <el-table-column prop="map50" label="mAP@50" width="100">
          <template #default="{ row }">
            <span v-if="row.map50">{{ (row.map50 * 100).toFixed(2) }}%</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusTagType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.startedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'COMPLETED' && row.bestModelPath"
              type="primary"
              size="small"
              @click="downloadModel(row)"
            >
              <el-icon><Download /></el-icon>
              下载权重
            </el-button>
            <el-button
              v-if="row.status === 'COMPLETED' && row.bestModelPath"
              type="success"
              size="small"
              @click="openTestDialog(row)"
            >
              <el-icon><VideoPlay /></el-icon>
              去测试
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty
        v-if="!loading && trainings.length === 0"
        description="暂无训练记录"
      />
    </el-card>

    <el-dialog
      v-model="testDialogVisible"
      title="模型测试"
      width="1000px"
      :close-on-click-modal="false"
    >
      <div class="test-dialog-content" v-loading="testLoading">
        <el-steps :active="testStep" align-center style="margin-bottom: 30px;">
          <el-step title="选择图片" />
          <el-step title="配置参数" />
          <el-step title="测试中" />
          <el-step title="查看结果" />
        </el-steps>

        <div v-if="testStep === 0" class="test-step-content">
          <el-alert
            title="上传测试图片"
            type="info"
            :closable="false"
            style="margin-bottom: 20px;"
          >
            <template #default>
              支持拖拽上传，可一次性上传多张图片
            </template>
          </el-alert>

          <el-upload
            ref="uploadRef"
            v-model:file-list="fileList"
            class="upload-demo"
            drag
            :auto-upload="false"
            :limit="10"
            :on-change="handleFileChange"
            :on-exceed="handleExceed"
            accept="image/*"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">
              拖拽文件到此处或 <em>点击上传</em>
            </div>
            <template #tip>
              <div class="el-upload__tip">
                支持 jpg/png/bmp/webp 格式，单次最多上传 10 张
              </div>
            </template>
          </el-upload>

          <div v-if="fileList.length > 0" class="file-preview">
            <h4>已选择的图片：</h4>
            <div class="preview-list">
              <div v-for="(file, index) in fileList" :key="index" class="preview-item">
                <el-image
                  :src="file.url"
                  fit="cover"
                  :preview-src-list="fileList.map(f => f.url)"
                  :initial-index="index"
                />
                <div class="file-name">{{ file.name }}</div>
                <el-button
                  type="danger"
                  size="small"
                  circle
                  @click="removeFile(index)"
                >
                  <el-icon><Delete /></el-icon>
                </el-button>
              </div>
            </div>
          </div>
        </div>

        <div v-if="testStep === 1" class="test-step-content">
          <el-alert
            title="配置测试参数"
            type="info"
            :closable="false"
            style="margin-bottom: 20px;"
          >
            <template #default>
              调整置信度和 IOU 阈值以获得最佳检测效果
            </template>
          </el-alert>

          <el-form :model="testParams" label-width="120px">
            <el-form-item label="置信度阈值">
              <el-slider
                v-model="testParams.confThreshold"
                :min="0.01"
                :max="1.00"
                :step="0.01"
                :marks="{
                  0.25: '0.25',
                  0.50: '0.50',
                  0.75: '0.75',
                  1.00: '1.00'
                }"
                show-input
              />
              <div class="form-tip">建议值：0.25，只显示置信度高于此阈值的检测结果</div>
            </el-form-item>

            <el-form-item label="IOU 阈值">
              <el-slider
                v-model="testParams.iouThreshold"
                :min="0.01"
                :max="1.00"
                :step="0.01"
                :marks="{
                  0.25: '0.25',
                  0.50: '0.50',
                  0.75: '0.75',
                  1.00: '1.00'
                }"
                show-input
              />
              <div class="form-tip">建议值：0.45，用于非极大值抑制（NMS）</div>
            </el-form-item>

            <el-form-item label="GPU 设备">
              <el-input
                v-model="testParams.device"
                placeholder="例如: 0 或 0,1"
              />
              <div class="form-tip">多 GPU 使用逗号分隔</div>
            </el-form-item>

            <el-form-item label="测试图片数">
              <el-tag type="info">{{ fileList.length }} 张</el-tag>
            </el-form-item>
          </el-form>
        </div>

        <div v-if="testStep === 2" class="test-step-content">
          <el-alert
            title="测试进行中"
            type="warning"
            :closable="false"
            style="margin-bottom: 20px;"
          >
            <template #default>
              正在使用 {{ selectedTraining?.modelType }} 进行推理，请稍候...
            </template>
          </el-alert>

          <div class="test-progress">
            <el-progress
              :percentage="testProgress"
              :status="testProgress === 100 ? 'success' : ''"
            />
            <div class="progress-info">
              <span>已处理：{{ processedCount }} / {{ totalCount }} 张图片</span>
              <span>预计剩余时间：{{ estimatedTime }}</span>
            </div>
          </div>
        </div>

        <div v-if="testStep === 3" class="test-step-content">
          <el-alert
            title="测试完成"
            type="success"
            :closable="false"
            style="margin-bottom: 20px;"
          >
            <template #default>
              共检测到 {{ totalDetections }} 个目标，平均每张图片 {{ averageDetections.toFixed(1) }} 个
            </template>
          </el-alert>

          <el-tabs v-model="activeResultTab" type="card">
            <el-tab-pane label="检测结果" name="results">
              <div class="results-grid">
                <div
                  v-for="(result, index) in testResults"
                  :key="index"
                  class="result-item"
                >
                  <div class="result-image-container">
                    <canvas
                      :ref="el => { if(el) canvasRefs[index] = el }"
                      class="result-canvas"
                    />
                    <div class="result-info">
                      <div class="info-item">
                        <span class="label">图片：</span>
                        <span class="value">{{ getFileName(result.image_path) }}</span>
                      </div>
                      <div class="info-item">
                        <span class="label">检测数：</span>
                        <span class="value">{{ result.total_detections }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </el-tab-pane>

            <el-tab-pane label="统计信息" name="stats">
              <el-descriptions :column="2" border>
                <el-descriptions-item label="总图片数">
                  {{ testResults.length }}
                </el-descriptions-item>
                <el-descriptions-item label="总检测数">
                  {{ totalDetections }}
                </el-descriptions-item>
                <el-descriptions-item label="平均检测数">
                  {{ averageDetections.toFixed(2) }}
                </el-descriptions-item>
                <el-descriptions-item label="置信度阈值">
                  {{ testParams.confThreshold }}
                </el-descriptions-item>
              </el-descriptions>

              <h4 style="margin: 20px 0 10px 0;">类别分布</h4>
              <div class="class-distribution">
                <div
                  v-for="(count, label) in classDistribution"
                  :key="label"
                  class="class-item"
                >
                  <el-tag>{{ label }}</el-tag>
                  <span class="count">{{ count }}</span>
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>
        </div>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="closeTestDialog">关闭</el-button>
          <el-button
            v-if="testStep === 0 && fileList.length > 0"
            type="primary"
            @click="nextStep"
          >
            下一步
          </el-button>
          <el-button
            v-if="testStep === 1"
            type="primary"
            @click="startTest"
            :loading="testLoading"
          >
            开始测试
          </el-button>
          <el-button
            v-if="testStep === 2"
            type="danger"
            @click="cancelTest"
          >
            取消测试
          </el-button>
          <el-button
            v-if="testStep === 3"
            type="primary"
            @click="resetTest"
          >
            重新测试
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Trophy, Refresh, Download, VideoPlay, UploadFilled, Delete } from '@element-plus/icons-vue'
import { trainingAPI, modelTestAPI } from '@/api'

const trainings = ref([])
const loading = ref(false)
const testDialogVisible = ref(false)
const selectedTraining = ref(null)
const fileList = ref([])
const uploadRef = ref(null)
const testStep = ref(0)
const testLoading = ref(false)
const testProgress = ref(0)
const processedCount = ref(0)
const totalCount = ref(0)
const estimatedTime = ref('计算中...')
const testResults = ref([])
const testTaskId = ref(null)
const testPollingTimer = ref(null)
const canvasRefs = ref({})
const activeResultTab = ref('results')

const testParams = ref({
  confThreshold: 0.25,
  iouThreshold: 0.45,
  device: '0'
})

const totalDetections = computed(() => {
  return testResults.value.reduce((sum, r) => sum + r.total_detections, 0)
})

const averageDetections = computed(() => {
  if (testResults.value.length === 0) return 0
  return totalDetections.value / testResults.value.length
})

const classDistribution = computed(() => {
  const distribution = {}
  testResults.value.forEach(result => {
    result.detections.forEach(det => {
      distribution[det.label] = (distribution[det.label] || 0) + 1
    })
  })
  return distribution
})

const loadTrainings = async () => {
  try {
    loading.value = true
    const res = await trainingAPI.getTrainingRecordsByUser()
    trainings.value = res.data || []
  } catch (error) {
    ElMessage.error('加载训练记录失败')
  } finally {
    loading.value = false
  }
}

const downloadModel = (training) => {
  ElMessage.info('模型下载功能将在下一阶段实现')
}

const openTestDialog = (training) => {
  selectedTraining.value = training
  testDialogVisible.value = true
  resetTest()
}

const closeTestDialog = () => {
  testDialogVisible.value = false
  stopTestPolling()
}

const resetTest = () => {
  testStep.value = 0
  fileList.value = []
  testParams.value = {
    confThreshold: 0.25,
    iouThreshold: 0.45,
    device: '0'
  }
  testResults.value = []
  testTaskId.value = null
  testProgress.value = 0
  processedCount.value = 0
  totalCount.value = 0
  estimatedTime.value = '计算中...'
  canvasRefs.value = {}
}

const handleFileChange = (file) => {
  if (file.raw) {
    const reader = new FileReader()
    reader.onload = (e) => {
      file.url = e.target.result
    }
    reader.readAsDataURL(file.raw)
  }
}

const handleExceed = () => {
  ElMessage.warning('最多只能上传 10 张图片')
}

const removeFile = (index) => {
  fileList.value.splice(index, 1)
}

const nextStep = () => {
  testStep.value++
}

const startTest = async () => {
  try {
    testLoading.value = true
    testStep.value = 2
    totalCount.value = fileList.value.length
    processedCount.value = 0
    testProgress.value = 0

    const formData = new FormData()
    fileList.value.forEach(file => {
      formData.append('files', file.raw)
    })
    formData.append('model_path', selectedTraining.value.bestModelPath)
    formData.append('conf_threshold', testParams.value.confThreshold)
    formData.append('iou_threshold', testParams.value.iouThreshold)
    formData.append('device', testParams.value.device)

    const res = await modelTestAPI.startTest(formData)
    testTaskId.value = res.data.task_id

    ElMessage.success('测试任务已启动')
    startTestPolling()

  } catch (error) {
    ElMessage.error('启动测试失败: ' + (error.response?.data?.message || error.message))
    testStep.value = 1
    testLoading.value = false
  }
}

const startTestPolling = () => {
  if (testPollingTimer.value) {
    clearInterval(testPollingTimer.value)
  }

  const startTime = Date.now()

  testPollingTimer.value = setInterval(async () => {
    try {
      const res = await modelTestAPI.getTestStatus(testTaskId.value)
      const status = res.data.status

      if (status === 'COMPLETED') {
        stopTestPolling()
        await loadTestResults()
        testStep.value = 3
        testLoading.value = false
        ElMessage.success('测试完成！')
      } else if (status === 'FAILED') {
        stopTestPolling()
        testLoading.value = false
        ElMessage.error('测试失败')
      } else if (status === 'RUNNING') {
        const progress = res.data.progress || 0
        testProgress.value = progress
        processedCount.value = Math.floor((progress / 100) * totalCount.value)

        const elapsed = (Date.now() - startTime) / 1000
        const remaining = (elapsed / progress) * (100 - progress)
        estimatedTime.value = formatTime(remaining)
      }
    } catch (error) {
      console.error('轮询测试状态失败', error)
    }
  }, 2000)
}

const stopTestPolling = () => {
  if (testPollingTimer.value) {
    clearInterval(testPollingTimer.value)
    testPollingTimer.value = null
  }
}

const loadTestResults = async () => {
  try {
    const res = await modelTestAPI.getTestResults(testTaskId.value)
    const results = res.data.results || []
    
    testResults.value = results

    await nextTick()
    await drawResults()
  } catch (error) {
    ElMessage.error('加载测试结果失败')
  }
}

const drawResults = async () => {
  for (let i = 0; i < testResults.value.length; i++) {
    const result = testResults.value[i]
    const canvas = canvasRefs.value[i]
    
    if (!canvas) continue

    const ctx = canvas.getContext('2d')
    const img = new Image()
    
    img.onload = () => {
      canvas.width = img.width
      canvas.height = img.height
      ctx.drawImage(img, 0, 0)

      const colors = [
        '#FF0000', '#00FF00', '#0000FF', '#FFFF00', '#FF00FF',
        '#00FFFF', '#FFA500', '#800080', '#008080', '#A52A2A'
      ]

      result.detections.forEach((det, idx) => {
        const color = colors[idx % colors.length]
        const { x1, y1, x2, y2 } = det.bbox

        ctx.strokeStyle = color
        ctx.lineWidth = 3
        ctx.strokeRect(x1, y1, x2 - x1, y2 - y1)

        ctx.fillStyle = color
        ctx.fillRect(x1, y1 - 25, ctx.measureText(det.label).width + 10, 25)

        ctx.fillStyle = '#FFFFFF'
        ctx.font = 'bold 16px Arial'
        ctx.fillText(
          `${det.label} ${(det.confidence * 100).toFixed(1)}%`,
          x1 + 5,
          y1 - 7
        )
      })
    }

    img.onerror = () => {
      console.error('Failed to load image:', result.image_path)
    }

    img.src = result.image_path
  }
}

const cancelTest = async () => {
  try {
    await ElMessageBox.confirm(
      '确定要取消当前测试吗？',
      '确认取消',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    await modelTestAPI.cancelTest(testTaskId.value)
    ElMessage.success('测试已取消')

    stopTestPolling()
    testStep.value = 1
    testLoading.value = false

  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('取消测试失败')
    }
  }
}

const getFileName = (path) => {
  return path.split('/').pop()
}

const formatTime = (seconds) => {
  if (seconds < 60) return `${Math.floor(seconds)}秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}分${Math.floor(seconds % 60)}秒`
  return `${Math.floor(seconds / 3600)}小时${Math.floor((seconds % 3600) / 60)}分`
}

const getStatusTagType = (status) => {
  const typeMap = {
    'PENDING': 'info',
    'RUNNING': 'warning',
    'COMPLETED': 'success',
    'FAILED': 'danger',
    'CANCELLED': 'info'
  }
  return typeMap[status] || 'info'
}

const getStatusText = (status) => {
  const textMap = {
    'PENDING': '等待中',
    'RUNNING': '运行中',
    'COMPLETED': '已完成',
    'FAILED': '失败',
    'CANCELLED': '已取消'
  }
  return textMap[status] || status
}

const formatTime2 = (time) => {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

onMounted(() => {
  loadTrainings()
})

onUnmounted(() => {
  stopTestPolling()
})
</script>

<style scoped>
.model-hub {
  padding: 10px;
}

.table-card {
  border-radius: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
}

.card-header .el-button {
  margin-left: auto;
}

.test-dialog-content {
  padding: 10px 0;
}

.test-step-content {
  min-height: 400px;
}

.upload-demo {
  width: 100%;
}

.file-preview {
  margin-top: 20px;
}

.file-preview h4 {
  margin-bottom: 15px;
  color: #303133;
}

.preview-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 15px;
}

.preview-item {
  position: relative;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  overflow: hidden;
}

.preview-item .el-image {
  width: 100%;
  height: 150px;
  display: block;
}

.file-name {
  padding: 8px;
  font-size: 12px;
  color: #606266;
  text-align: center;
  background: #f5f7fa;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-item .el-button {
  position: absolute;
  top: 5px;
  right: 5px;
  width: 28px;
  height: 28px;
  padding: 0;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
}

.test-progress {
  padding: 40px 20px;
}

.progress-info {
  display: flex;
  justify-content: space-between;
  margin-top: 20px;
  font-size: 14px;
  color: #606266;
}

.results-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(400px, 1fr));
  gap: 20px;
  max-height: 600px;
  overflow-y: auto;
}

.result-item {
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  overflow: hidden;
}

.result-image-container {
  position: relative;
}

.result-canvas {
  width: 100%;
  height: auto;
  display: block;
}

.result-info {
  padding: 12px;
  background: #f5f7fa;
  border-top: 1px solid #dcdfe6;
}

.info-item {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 14px;
}

.info-item .label {
  color: #909399;
}

.info-item .value {
  color: #303133;
  font-weight: 600;
}

.class-distribution {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.class-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 4px;
}

.class-item .count {
  font-weight: 600;
  color: #409eff;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
