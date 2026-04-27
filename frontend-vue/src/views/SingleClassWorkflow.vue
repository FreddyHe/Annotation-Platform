<template>
  <div class="single-class-workflow">
    <div class="module-header">
      <div>
        <h1>单类别训练与检测</h1>
        <p>训练任务、模型管理和单图检测集中在同一工作流中。</p>
      </div>
      <el-space>
        <el-button :loading="loadingTasks || loadingModels" @click="refreshAll">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button type="primary" @click="activeTab = 'train'">
          <el-icon><Plus /></el-icon>
          新建训练
        </el-button>
      </el-space>
    </div>

    <div class="overview-grid">
      <div class="metric-tile">
        <span class="metric-label">训练任务</span>
        <strong>{{ tasks.length }}</strong>
        <small>{{ runningTasks.length }} 个进行中</small>
      </div>
      <div class="metric-tile">
        <span class="metric-label">可用模型</span>
        <strong>{{ availableModels.length }}</strong>
        <small>含 {{ customModels.length }} 个自定义模型</small>
      </div>
      <div class="metric-tile">
        <span class="metric-label">最近训练</span>
        <strong>{{ latestTask ? statusText(latestTask.status) : '-' }}</strong>
        <small>{{ latestTask?.modelName || '暂无任务' }}</small>
      </div>
      <div class="metric-tile">
        <span class="metric-label">最近检测</span>
        <strong>{{ detectionHistory[0]?.count ?? '-' }}</strong>
        <small>{{ detectionHistory[0]?.className || '暂无记录' }}</small>
      </div>
    </div>

    <el-alert
      v-if="errorMessage"
      type="error"
      :title="errorMessage"
      show-icon
      :closable="true"
      @close="errorMessage = ''"
      class="module-alert"
    />

    <el-tabs v-model="activeTab" class="workflow-tabs">
      <el-tab-pane label="训练配置" name="train">
        <div class="content-grid train-grid">
          <el-card shadow="never">
            <template #header>
              <div class="card-header">
                <span>数据源配置</span>
                <el-tag type="success" size="small">AutoML</el-tag>
              </div>
            </template>

            <el-form ref="formRef" :model="trainingForm" :rules="trainingRules" label-position="top">
              <el-form-item label="数据来源">
                <el-select v-model="trainingForm.datasetSource" style="width: 100%;">
                  <el-option label="Roboflow 数据集" value="ROBOFLOW" />
                  <el-option label="公开 URL ZIP" value="URL_ZIP" />
                  <el-option label="上传 ZIP 数据集" value="UPLOAD_ZIP" />
                </el-select>
              </el-form-item>

              <el-form-item v-if="trainingForm.datasetSource === 'ROBOFLOW'" label="Roboflow 下载命令" prop="downloadCommand">
                <el-input
                  v-model="trainingForm.downloadCommand"
                  type="textarea"
                  :rows="7"
                  maxlength="2000"
                  show-word-limit
                  placeholder="粘贴 Roboflow URL、curl 命令或 Python SDK 下载代码"
                />
              </el-form-item>

              <el-form-item v-else-if="trainingForm.datasetSource === 'URL_ZIP'" label="数据集 ZIP URL" prop="datasetUri">
                <el-input v-model="trainingForm.datasetUri" placeholder="https://example.com/dataset.zip" clearable />
              </el-form-item>

              <el-form-item v-else label="上传 ZIP 数据集" prop="datasetUri">
                <el-upload
                  :http-request="uploadDatasetPackage"
                  :limit="1"
                  :on-remove="removeDatasetPackage"
                  :on-exceed="handleDatasetExceed"
                  accept=".zip"
                  drag
                >
                  <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
                  <div class="el-upload__text">拖拽 YOLO/COCO 数据集 ZIP 或 <em>点击上传</em></div>
                  <template #tip>
                    <div class="el-upload__tip">
                      ZIP 内需包含 data.yaml，或 COCO JSON + 图片。已上传：{{ uploadedDatasetName || '无' }}
                    </div>
                  </template>
                </el-upload>
              </el-form-item>

              <div class="dataset-actions">
                <el-button type="primary" size="large" :loading="submitting || inspectingDataset" @click="handleAutoTrain">
                  <el-icon><VideoPlay /></el-icon>
                  下载数据并自动训练
                </el-button>
                <el-button size="large" @click="activeTab = 'tasks'">查看训练任务</el-button>
                <el-tag v-if="datasetInspection" :type="datasetInspection.valid ? 'success' : 'danger'">
                  {{ datasetInspection.valid ? '已读取数据集' : '数据集需修正' }}
                </el-tag>
              </div>

              <div v-if="datasetInspection" class="inspection-panel">
                <div class="inspection-summary">
                  <div>
                    <span>格式</span>
                    <strong>{{ datasetInspection.format || '-' }}</strong>
                  </div>
                  <div>
                    <span>类别数</span>
                    <strong>{{ datasetInspection.classCount ?? 0 }}</strong>
                  </div>
                  <div>
                    <span>图片数</span>
                    <strong>{{ datasetInspection.imageCount ?? 0 }}</strong>
                  </div>
                  <div>
                    <span>标签数</span>
                    <strong>{{ datasetInspection.labelCount ?? 0 }}</strong>
                  </div>
                </div>
                <el-alert
                  v-if="datasetInspection.warnings?.length"
                  type="warning"
                  :closable="false"
                  show-icon
                  class="inspection-warning"
                >
                  <template #default>
                    <ul class="warning-list">
                      <li v-for="warning in datasetInspection.warnings" :key="warning">{{ warning }}</li>
                    </ul>
                  </template>
                </el-alert>
                <div v-if="datasetInspection.classes?.length" class="inspection-classes">
                  <el-tag v-for="cls in datasetInspection.classes" :key="`${cls.class_id}-${cls.name}`" size="small">
                    {{ cls.name }}
                  </el-tag>
                </div>
                <div v-if="autoTrainingProfile" class="automl-panel">
                  <span>AutoML 参数</span>
                  <el-tag size="small">Epochs {{ autoTrainingProfile.epochs }}</el-tag>
                  <el-tag size="small">Batch {{ autoTrainingProfile.batchSize }}</el-tag>
                  <el-tag size="small">Img {{ autoTrainingProfile.imageSize }}</el-tag>
                  <el-tag size="small">LR {{ autoTrainingProfile.learningRate }}</el-tag>
                </div>
              </div>
            </el-form>
          </el-card>

          <el-card shadow="never">
            <template #header>
              <div class="card-header">
                <span>当前任务状态</span>
                <el-button text @click="activeTab = 'tasks'">查看全部</el-button>
              </div>
            </template>

            <el-empty v-if="!selectedTask && tasks.length === 0" description="暂无训练任务" />
            <div v-else class="task-focus">
              <div class="task-focus-title">
                <strong>{{ selectedTask?.modelName || latestTask?.modelName }}</strong>
                <el-tag :type="statusTagType((selectedTask || latestTask)?.status)" size="small">
                  {{ statusText((selectedTask || latestTask)?.status) }}
                </el-tag>
              </div>
              <el-progress :percentage="progressPercent(selectedTask || latestTask)" :status="progressStatus(selectedTask || latestTask)" />
              <p>{{ (selectedTask || latestTask)?.statusMessage || '等待状态更新' }}</p>
              <pre class="log-box">{{ (selectedTask || latestTask)?.trainingLog || '暂无训练日志' }}</pre>
            </div>
          </el-card>
        </div>
      </el-tab-pane>

      <el-tab-pane label="训练任务" name="tasks">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>训练任务</span>
              <el-button :loading="loadingTasks" @click="refreshTasks">刷新</el-button>
            </div>
          </template>

          <el-table
            :data="tasks"
            v-loading="loadingTasks"
            stripe
            highlight-current-row
            empty-text="暂无训练任务"
            @current-change="handleRowClick"
          >
            <el-table-column prop="modelName" label="模型名称" min-width="160" />
            <el-table-column prop="targetClassName" label="目标类别" width="130">
              <template #default="{ row }">{{ row.targetClassName || '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="进度" width="140">
              <template #default="{ row }">
                <el-progress :percentage="progressPercent(row)" :stroke-width="8" />
              </template>
            </el-table-column>
            <el-table-column prop="epochs" label="Epochs" width="80" align="center" />
            <el-table-column label="mAP50" width="90" align="center">
              <template #default="{ row }">{{ formatMetric(row.mapScore) }}</template>
            </el-table-column>
            <el-table-column label="创建时间" width="170">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'COMPLETED'" type="primary" link size="small" @click.stop="goToDetection(row)">检测</el-button>
                <el-button v-if="row.status === 'FAILED'" type="warning" link size="small" @click.stop="retryTask(row)">重试</el-button>
                <el-button type="primary" link size="small" @click.stop="selectTask(row)">详情</el-button>
                <el-button type="danger" link size="small" @click.stop="deleteModel(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="模型管理" name="models">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>可用模型</span>
              <el-button :loading="loadingModels" @click="loadModels">刷新</el-button>
            </div>
          </template>

          <el-empty v-if="availableModels.length === 0" description="暂无可用模型" />
          <el-table v-else :data="availableModels" stripe>
            <el-table-column prop="modelName" label="模型名称" min-width="180" />
            <el-table-column label="类别" min-width="220">
              <template #default="{ row }">
                <el-tag v-for="cls in row.classes" :key="cls.classId" size="small" class="class-tag">
                  {{ cls.cnName || cls.className }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="mAP50" width="100">
              <template #default="{ row }">{{ formatMetric(row.mapScore) }}</template>
            </el-table-column>
            <el-table-column label="模型路径" min-width="260">
              <template #default="{ row }">
                <span class="path-text">{{ row.modelPath || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="goToDetection(row)">用于检测</el-button>
                <el-button v-if="row.id !== 'builtin'" type="danger" link size="small" @click="deleteModel(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="单类别检测" name="detect">
        <div class="content-grid detect-grid">
          <el-card shadow="never">
            <template #header>
              <div class="card-header">
                <span>检测参数</span>
                <el-tag type="info" size="small">{{ currentModel?.modelName || '未选择模型' }}</el-tag>
              </div>
            </template>

            <el-form :model="detectionForm" label-position="top">
              <el-form-item label="检测模型">
                <el-select v-model="selectedModelId" filterable style="width: 100%;">
                  <el-option-group label="内置模型">
                    <el-option v-for="m in builtinModels" :key="m.id" :label="m.modelName" :value="m.id" />
                  </el-option-group>
                  <el-option-group v-if="customModels.length > 0" label="自定义训练模型">
                    <el-option v-for="m in customModels" :key="m.id" :label="m.modelName" :value="m.id" />
                  </el-option-group>
                </el-select>
              </el-form-item>

              <el-form-item label="检测类别">
                <el-select v-model="detectionForm.classId" :disabled="currentClasses.length === 0" style="width: 100%;">
                  <el-option
                    v-for="cls in currentClasses"
                    :key="cls.classId"
                    :label="cls.cnName || cls.className"
                    :value="cls.classId"
                  />
                </el-select>
              </el-form-item>

              <el-row :gutter="16">
                <el-col :span="12">
                  <el-form-item label="置信度阈值">
                    <el-slider v-model="detectionForm.confidenceThreshold" :min="0" :max="1" :step="0.05" show-input />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="IOU阈值">
                    <el-slider v-model="detectionForm.iouThreshold" :min="0" :max="1" :step="0.05" show-input />
                  </el-form-item>
                </el-col>
              </el-row>

              <el-form-item label="上传图片">
                <el-upload
                  ref="uploadRef"
                  :auto-upload="false"
                  :on-change="handleFileChange"
                  :on-remove="handleFileRemove"
                  :limit="1"
                  :on-exceed="handleExceed"
                  accept="image/jpeg,image/png,image/webp"
                  drag
                >
                  <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
                  <div class="el-upload__text">拖拽图片到此处或 <em>点击上传</em></div>
                  <template #tip>
                    <div class="el-upload__tip">JPG、PNG、WEBP，最大 10MB</div>
                  </template>
                </el-upload>
              </el-form-item>

              <el-button type="primary" size="large" :loading="detecting" :disabled="!canDetect" @click="handleDetect">
                <el-icon><Search /></el-icon>
                开始检测
              </el-button>
            </el-form>
          </el-card>

          <el-card shadow="never">
            <template #header>
              <div class="card-header">
                <span>检测结果</span>
                <el-space v-if="detectionResult">
                  <el-tag :type="resultCount > 0 ? 'success' : 'warning'" size="small">{{ resultCount }} 个目标</el-tag>
                  <el-button text @click="exportDetectionJson">导出JSON</el-button>
                  <el-button text @click="exportDetectionImage">导出图片</el-button>
                </el-space>
              </div>
            </template>

            <el-empty v-if="!detectionResult" description="暂无检测结果" />
            <div v-else class="detection-result">
              <el-alert
                v-if="resultCount === 0"
                type="warning"
                title="未检测到目标"
                description="可降低置信度阈值、检查类别是否正确，或补充样本重新训练。"
                show-icon
                :closable="false"
              />
              <el-alert
                v-else-if="lowConfidence"
                type="warning"
                title="存在低置信度结果"
                description="建议复核标注质量和训练样本覆盖度。"
                show-icon
                :closable="false"
              />

              <div class="image-preview">
                <el-image :src="resultImageSrc" fit="contain" />
              </div>

              <el-table v-if="resultCount > 0" :data="detectionResult.detections" border stripe>
                <el-table-column type="index" label="#" width="56" />
                <el-table-column prop="class" label="类别" width="140" />
                <el-table-column label="置信度" width="110">
                  <template #default="{ row }">
                    <el-tag :type="confidenceType(row.confidence)" size="small">{{ (row.confidence * 100).toFixed(1) }}%</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="边界框">
                  <template #default="{ row }">
                    {{ formatBbox(row.bbox_absolute || row.bbox) }}
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-card>
        </div>

        <el-card shadow="never" class="history-card">
          <template #header>
            <div class="card-header">
              <span>最近检测任务</span>
              <el-button text :disabled="detectionHistory.length === 0" @click="clearDetectionHistory">清空</el-button>
            </div>
          </template>
          <el-table :data="detectionHistory" empty-text="暂无检测历史" stripe>
            <el-table-column prop="fileName" label="图片" min-width="160" />
            <el-table-column prop="modelName" label="模型" min-width="180" />
            <el-table-column prop="className" label="类别" width="140" />
            <el-table-column prop="count" label="目标数" width="90" align="center" />
            <el-table-column label="平均置信度" width="120">
              <template #default="{ row }">{{ row.avgConfidence ? `${(row.avgConfidence * 100).toFixed(1)}%` : '-' }}</template>
            </el-table-column>
            <el-table-column label="时间" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createTrainingTask,
  clearDetectionHistoryRecords,
  deleteCustomModel,
  detectSingleClass,
  getAvailableModels,
  getBuiltinModelInfo,
  getModelStatus,
  getTrainingLogs,
  inspectTrainingDataset,
  listDetectionHistory,
  listTrainingTasks,
  retryTrainingTask,
  uploadTrainingDataset
} from '@/api/customModel'

const route = useRoute()
const router = useRouter()

const formRef = ref(null)
const uploadRef = ref(null)
const activeTab = ref(route.query.tab || (route.path.includes('single-class-detection') ? 'detect' : 'train'))
const submitting = ref(false)
const detecting = ref(false)
const inspectingDataset = ref(false)
const loadingTasks = ref(false)
const loadingModels = ref(false)
const errorMessage = ref('')

const tasks = ref([])
const builtinModels = ref([])
const customModels = ref([])
const selectedTask = ref(null)
const selectedFile = ref(null)
const selectedModelId = ref('builtin')
const detectionResult = ref(null)
const detectionHistory = ref([])
const uploadedDatasetName = ref('')
const datasetInspection = ref(null)
const autoTrainingProfile = ref(null)
let pollTimer = null

const trainingForm = reactive({
  modelName: '',
  projectId: null,
  targetClassName: '',
  datasetSource: 'ROBOFLOW',
  downloadCommand: '',
  datasetUri: '',
  epochs: 50,
  batchSize: 16,
  imageSize: 640,
  learningRate: 0.01,
  usePretrained: true
})

const detectionForm = reactive({
  classId: null,
  confidenceThreshold: 0.5,
  iouThreshold: 0.45
})

const trainingRules = {
  downloadCommand: [{ validator: validateDownloadCommand, trigger: 'blur' }],
  datasetUri: [{ validator: validateDatasetUri, trigger: 'blur' }]
}

const allModels = computed(() => [...builtinModels.value, ...customModels.value])
const availableModels = computed(() => allModels.value.filter(model => model.modelPath))
const runningTasks = computed(() => tasks.value.filter(task => isInProgress(task.status)))
const latestTask = computed(() => tasks.value[0] || null)
const currentModel = computed(() => allModels.value.find(model => model.id === selectedModelId.value))
const currentClasses = computed(() => currentModel.value?.classes || [])
const currentClass = computed(() => currentClasses.value.find(cls => cls.classId === detectionForm.classId))
const resultCount = computed(() => detectionResult.value?.detections?.length || 0)
const resultImageSrc = computed(() => detectionResult.value?.image_base64 ? `data:image/jpeg;base64,${detectionResult.value.image_base64}` : '')
const lowConfidence = computed(() => (detectionResult.value?.detections || []).some(item => item.confidence < 0.55))
const canDetect = computed(() => Boolean(selectedFile.value && currentModel.value?.modelPath && detectionForm.classId !== null && !detecting.value))

function validateDownloadCommand(rule, value, callback) {
  if (trainingForm.datasetSource !== 'ROBOFLOW') {
    callback()
    return
  }
  if (!value || value.trim().length < 20) {
    callback(new Error('请输入完整的 Roboflow 下载命令'))
    return
  }
  callback()
}

function validateDatasetUri(rule, value, callback) {
  if (trainingForm.datasetSource === 'ROBOFLOW') {
    callback()
    return
  }
  if (!value || value.trim().length < 4) {
    callback(new Error('请填写或上传数据集地址'))
    return
  }
  if (trainingForm.datasetSource === 'URL_ZIP' && !/^https?:\/\//i.test(value)) {
    callback(new Error('请输入 http/https ZIP 地址'))
    return
  }
  callback()
}

function isInProgress(status) {
  return ['PENDING', 'DOWNLOADING', 'CONVERTING', 'TRAINING'].includes(status)
}

function statusTagType(status) {
  return {
    PENDING: 'info',
    DOWNLOADING: 'warning',
    CONVERTING: 'warning',
    TRAINING: 'primary',
    COMPLETED: 'success',
    FAILED: 'danger'
  }[status] || 'info'
}

function statusText(status) {
  return {
    PENDING: '等待中',
    DOWNLOADING: '下载数据',
    CONVERTING: '格式转换',
    TRAINING: '训练中',
    COMPLETED: '已完成',
    FAILED: '失败'
  }[status] || status || '-'
}

function progressPercent(task) {
  if (!task) return 0
  if (task.status === 'COMPLETED') return 100
  if (task.status === 'FAILED') return Math.round((task.progress || 0) * 100)
  return Math.round((task.progress || 0) * 100)
}

function progressStatus(task) {
  if (!task) return undefined
  if (task.status === 'COMPLETED') return 'success'
  if (task.status === 'FAILED') return 'exception'
  return undefined
}

function formatMetric(value) {
  return value === null || value === undefined ? '-' : `${(value * 100).toFixed(1)}%`
}

function formatTime(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN')
}

function formatBbox(values) {
  if (!Array.isArray(values)) return '-'
  return `[${values.map(value => Number(value).toFixed(1)).join(', ')}]`
}

function confidenceType(value) {
  if (value >= 0.8) return 'success'
  if (value >= 0.6) return 'warning'
  return 'danger'
}

async function refreshAll() {
  await Promise.all([refreshTasks(), loadModels(), loadDetectionHistory()])
}

async function refreshTasks() {
  loadingTasks.value = true
  try {
    const response = await listTrainingTasks()
    tasks.value = response.data || []
    if (selectedTask.value) {
      selectedTask.value = tasks.value.find(task => task.id === selectedTask.value.id) || selectedTask.value
    }
    updatePolling()
  } catch (error) {
    errorMessage.value = '训练任务加载失败'
    console.error('训练任务加载失败:', error)
  } finally {
    loadingTasks.value = false
  }
}

async function loadModels() {
  loadingModels.value = true
  try {
    const builtinResponse = await getBuiltinModelInfo()
    const builtinData = builtinResponse.data || {}
    builtinModels.value = [{
      id: 'builtin',
      modelName: '内置 VisDrone 检测模型',
      modelPath: builtinData.model_path,
      classes: (builtinData.classes || []).map(item => ({
        classId: item.class_id,
        className: item.name,
        cnName: item.cn_name || item.name
      }))
    }]
  } catch (error) {
    builtinModels.value = [{
      id: 'builtin',
      modelName: '内置 VisDrone 检测模型',
      modelPath: '/root/autodl-fs/xingmu_jiancepingtai/runs/detect/train7/weights/best.pt',
      classes: []
    }]
    console.warn('加载内置模型失败:', error)
  }

  try {
    const customResponse = await getAvailableModels()
    customModels.value = (customResponse.data || []).map(model => ({
      ...model,
      id: `custom_${model.id}`,
      sourceId: model.id,
      classes: model.classes || []
    }))
  } catch (error) {
    customModels.value = []
    console.warn('加载自定义模型失败:', error)
  } finally {
    loadingModels.value = false
    applyModelFromRoute()
    ensureClassSelected()
  }
}

function applyModelFromRoute() {
  if (!route.query.modelId) return
  const targetId = String(route.query.modelId).startsWith('custom_')
    ? route.query.modelId
    : `custom_${route.query.modelId}`
  if (allModels.value.some(model => model.id === targetId)) {
    selectedModelId.value = targetId
    activeTab.value = 'detect'
  }
}

function applyTrainingFromRoute() {
  if (route.query.targetClassName) {
    activeTab.value = 'train'
  }
}

function ensureClassSelected() {
  if (currentClasses.value.length === 0) {
    detectionForm.classId = null
    return
  }
  if (!currentClasses.value.some(cls => cls.classId === detectionForm.classId)) {
    detectionForm.classId = currentClasses.value[0].classId
  }
}

function autoModelNameFromInspection(inspection) {
  const names = (inspection?.classes || []).map(item => item.name).filter(Boolean)
  const timestamp = new Date().toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).replace(/[\/:\s]/g, '')
  if (names.length === 1) return `${names[0]}检测模型-${timestamp}`
  if (names.length > 1) return `${names[0]}等${names.length}类检测模型-${timestamp}`
  return `AutoML检测模型-${timestamp}`
}

function targetClassNameFromInspection(inspection) {
  return (inspection?.classes || []).map(item => item.name).filter(Boolean).join(', ')
}

function buildAutoTrainingProfile(inspection) {
  const imageCount = Number(inspection?.imageCount || 0)
  const classCount = Number(inspection?.classCount || 1)
  const hasValidation = Boolean(inspection?.splits?.val || inspection?.splits?.valid)

  let epochs = 50
  if (imageCount <= 20) epochs = 10
  else if (imageCount <= 100) epochs = 30
  else if (imageCount <= 500) epochs = 60
  else epochs = 80

  let batchSize = 16
  if (imageCount <= 80 || classCount >= 12) batchSize = 4
  else if (imageCount <= 400 || classCount >= 6) batchSize = 8

  const imageSize = imageCount > 0 && imageCount < 80 ? 640 : 640
  const learningRate = batchSize <= 4 ? 0.003 : batchSize <= 8 ? 0.005 : 0.01

  return {
    epochs: hasValidation ? epochs : Math.max(epochs, 30),
    batchSize,
    imageSize,
    learningRate,
    usePretrained: true
  }
}

async function handleAutoTrain() {
  const valid = await formRef.value?.validateField(['downloadCommand', 'datasetUri']).then(() => true).catch(() => false)
  if (!valid) return

  inspectingDataset.value = true
  submitting.value = true
  datasetInspection.value = null
  autoTrainingProfile.value = null
  try {
    const inspectionResponse = await inspectTrainingDataset({
      datasetSource: trainingForm.datasetSource,
      datasetUri: trainingForm.datasetUri,
      downloadCommand: trainingForm.downloadCommand
    })
    datasetInspection.value = inspectionResponse.data || null
    if (!datasetInspection.value?.valid) {
      ElMessage.warning('数据集读取失败或结构不完整')
      return
    }

    const profile = buildAutoTrainingProfile(datasetInspection.value)
    autoTrainingProfile.value = profile
    await createTrainingTask({
      modelName: autoModelNameFromInspection(datasetInspection.value),
      targetClassName: targetClassNameFromInspection(datasetInspection.value),
      datasetSource: trainingForm.datasetSource,
      datasetUri: trainingForm.datasetUri,
      downloadCommand: trainingForm.downloadCommand,
      automl: true,
      ...profile
    })
    ElMessage.success('已读取数据集，AutoML 训练任务已创建')
    await refreshTasks()
    activeTab.value = 'tasks'
    startPolling()
  } catch (error) {
    errorMessage.value = error.message || '创建 AutoML 训练任务失败'
  } finally {
    submitting.value = false
    inspectingDataset.value = false
  }
}

async function uploadDatasetPackage(options) {
  const file = options.file
  if (!file.name.toLowerCase().endsWith('.zip')) {
    ElMessage.error('训练数据集仅支持 ZIP 压缩包')
    options.onError?.(new Error('仅支持ZIP'))
    return
  }
  const formData = new FormData()
  formData.append('file', file)
  try {
    const response = await uploadTrainingDataset(formData)
    trainingForm.datasetUri = response.data?.absolutePath || response.data?.path || ''
    uploadedDatasetName.value = response.data?.filename || file.name
    ElMessage.success('数据集上传成功')
    options.onSuccess?.(response.data)
  } catch (error) {
    options.onError?.(error)
  }
}

function removeDatasetPackage() {
  trainingForm.datasetUri = ''
  uploadedDatasetName.value = ''
}

function handleDatasetExceed() {
  ElMessage.warning('一次只能上传一个数据集 ZIP')
}

function handleRowClick(row) {
  if (row) selectTask(row)
}

async function selectTask(row) {
  selectedTask.value = row
  activeTab.value = 'train'
  try {
    const response = await getTrainingLogs(row.id)
    selectedTask.value = { ...row, trainingLog: response.data || row.trainingLog }
  } catch {
    selectedTask.value = row
  }
}

async function retryTask(row) {
  try {
    await retryTrainingTask(row.sourceId || row.id)
    ElMessage.success('训练任务已重新提交')
    await refreshTasks()
  } catch (error) {
    errorMessage.value = error.message || '重试失败'
  }
}

async function deleteModel(row) {
  const rawId = row.sourceId || row.id
  if (rawId === 'builtin') return
  try {
    await ElMessageBox.confirm(`确定删除“${row.modelName}”吗？`, '删除模型', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteCustomModel(rawId)
    ElMessage.success('已删除')
    selectedTask.value = null
    await refreshAll()
  } catch (error) {
    if (error !== 'cancel') {
      errorMessage.value = error.message || '删除失败'
    }
  }
}

function goToDetection(row) {
  const modelId = row.id === 'builtin' ? 'builtin' : `custom_${row.sourceId || row.id}`
  selectedModelId.value = modelId
  activeTab.value = 'detect'
  ensureClassSelected()
  router.replace({ path: '/model-training', query: { tab: 'detect', modelId } })
}

function handleFileChange(file) {
  const raw = file.raw
  if (!raw) return
  const isImage = ['image/jpeg', 'image/png', 'image/webp'].includes(raw.type)
  if (!isImage) {
    ElMessage.error('仅支持 JPG、PNG、WEBP 图片')
    uploadRef.value?.clearFiles()
    selectedFile.value = null
    return
  }
  if (raw.size / 1024 / 1024 > 10) {
    ElMessage.error('图片不能超过 10MB')
    uploadRef.value?.clearFiles()
    selectedFile.value = null
    return
  }
  selectedFile.value = raw
}

function handleFileRemove() {
  selectedFile.value = null
}

function handleExceed() {
  ElMessage.warning('一次只能检测一张图片')
}

async function handleDetect() {
  if (!canDetect.value) {
    ElMessage.warning('请选择模型、类别并上传图片')
    return
  }
  detecting.value = true
  detectionResult.value = null
  try {
    const formData = new FormData()
    formData.append('image', selectedFile.value)
    formData.append('class_id', detectionForm.classId)
    formData.append('model_path', currentModel.value.modelPath)
    formData.append('model_id', currentModel.value.id)
    formData.append('model_name', currentModel.value.modelName)
    formData.append('class_name', currentClass.value?.cnName || currentClass.value?.className || '')
    formData.append('confidence_threshold', detectionForm.confidenceThreshold)
    formData.append('iou_threshold', detectionForm.iouThreshold)
    const response = await detectSingleClass(formData)
    const payload = response.data || {}
    if (payload.success === false) {
      throw new Error(payload.message || '检测失败')
    }
    detectionResult.value = payload
    await loadDetectionHistory()
    ElMessage.success('检测完成')
  } catch (error) {
    errorMessage.value = error.message || '检测失败'
  } finally {
    detecting.value = false
  }
}

function exportDetectionJson() {
  if (!detectionResult.value) return
  const blob = new Blob([JSON.stringify(detectionResult.value, null, 2)], { type: 'application/json' })
  downloadBlob(blob, `single-class-detection-${Date.now()}.json`)
}

function exportDetectionImage() {
  if (!detectionResult.value?.image_base64) return
  const byteCharacters = atob(detectionResult.value.image_base64)
  const byteNumbers = Array.from(byteCharacters, char => char.charCodeAt(0))
  const blob = new Blob([new Uint8Array(byteNumbers)], { type: 'image/jpeg' })
  downloadBlob(blob, `single-class-detection-${Date.now()}.jpg`)
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

function clearDetectionHistory() {
  clearDetectionHistoryRecords().then(() => {
    detectionHistory.value = []
    ElMessage.success('检测历史已清空')
  }).catch(error => {
    errorMessage.value = error.message || '清空检测历史失败'
  })
}

async function loadDetectionHistory() {
  try {
    const response = await listDetectionHistory()
    detectionHistory.value = (response.data || []).map(item => ({
      id: item.id,
      fileName: basename(item.imagePath),
      modelName: item.modelName || item.modelId || '-',
      className: item.className || item.classId || '-',
      count: item.detectionCount || 0,
      avgConfidence: item.averageConfidence || 0,
      createdAt: item.createdAt
    }))
  } catch (error) {
    console.warn('加载检测历史失败:', error)
  }
}

function basename(path) {
  if (!path) return '-'
  const normalized = String(path).replace(/\\/g, '/')
  return normalized.substring(normalized.lastIndexOf('/') + 1)
}

function updatePolling() {
  if (tasks.value.some(task => isInProgress(task.status))) {
    startPolling()
  } else {
    stopPolling()
  }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    const inProgress = tasks.value.filter(task => isInProgress(task.status))
    for (const task of inProgress) {
      try {
        await getModelStatus(task.id)
      } catch {
        // 状态同步失败不打断列表刷新
      }
    }
    await refreshTasks()
    await loadModels()
  }, 5000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(selectedModelId, ensureClassSelected)

watch(() => trainingForm.datasetSource, source => {
  if (source === 'ROBOFLOW') {
    trainingForm.datasetUri = ''
    uploadedDatasetName.value = ''
  } else {
    trainingForm.downloadCommand = ''
  }
  datasetInspection.value = null
  autoTrainingProfile.value = null
  formRef.value?.clearValidate(['downloadCommand', 'datasetUri'])
})

watch(() => [trainingForm.downloadCommand, trainingForm.datasetUri], () => {
  datasetInspection.value = null
  autoTrainingProfile.value = null
})

watch(activeTab, value => {
  if (value === 'detect') {
    router.replace({ path: '/model-training', query: { ...route.query, tab: 'detect' } })
  }
})

onMounted(async () => {
  applyTrainingFromRoute()
  await refreshAll()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.single-class-workflow {
  max-width: 1440px;
}

.module-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.module-header h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--gray-900);
}

.module-header p {
  margin: 6px 0 0;
  color: var(--gray-500);
  font-size: 13px;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.metric-tile {
  min-height: 92px;
  padding: 14px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-md);
  background: #fff;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.metric-label,
.metric-tile small {
  color: var(--gray-500);
  font-size: 12px;
}

.metric-tile strong {
  color: var(--gray-900);
  font-size: 24px;
  font-weight: 600;
}

.module-alert {
  margin-bottom: 14px;
}

.workflow-tabs {
  background: #fff;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-md);
  padding: 12px 16px 18px;
}

.content-grid {
  display: grid;
  gap: 16px;
}

.train-grid,
.detect-grid {
  grid-template-columns: minmax(420px, 0.95fr) minmax(520px, 1.05fr);
}

.card-header,
.task-focus-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.task-focus {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.task-focus p {
  margin: 0;
  color: var(--gray-600);
  font-size: 13px;
}

.log-box {
  min-height: 220px;
  max-height: 360px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  border-radius: var(--radius-md);
  background: #111827;
  color: #d1d5db;
  font-size: 12px;
  line-height: 1.6;
}

.dataset-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: -4px 0 16px;
}

.inspection-panel {
  padding: 12px;
  margin-bottom: 16px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-md);
  background: var(--gray-50);
}

.inspection-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.inspection-summary div {
  padding: 10px;
  border-radius: var(--radius-md);
  background: #fff;
}

.inspection-summary span {
  display: block;
  margin-bottom: 4px;
  color: var(--gray-500);
  font-size: 12px;
}

.inspection-summary strong {
  color: var(--gray-900);
  font-size: 18px;
}

.inspection-warning {
  margin-top: 10px;
}

.warning-list {
  margin: 0;
  padding-left: 18px;
}

.inspection-classes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.automl-panel {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--gray-200);
}

.automl-panel span {
  color: var(--gray-600);
  font-size: 12px;
}

.class-tag {
  margin: 2px 4px 2px 0;
}

.path-text {
  color: var(--gray-500);
  font-size: 12px;
  word-break: break-all;
}

.detection-result {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.image-preview {
  min-height: 360px;
  border: 1px solid var(--gray-200);
  border-radius: var(--radius-md);
  background: var(--gray-50);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.image-preview :deep(.el-image) {
  width: 100%;
  max-height: 520px;
}

.history-card {
  margin-top: 16px;
}

@media (max-width: 1100px) {
  .overview-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .inspection-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .train-grid,
  .detect-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .module-header {
    flex-direction: column;
  }

  .overview-grid {
    grid-template-columns: 1fr;
  }
}
</style>
