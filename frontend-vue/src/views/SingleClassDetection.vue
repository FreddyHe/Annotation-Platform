<template>
  <div class="single-class-detection">
    <el-page-header @back="goBack" content="单类别检测">
      <template #extra>
        <el-tag type="info" size="large">
          <el-icon><Monitor /></el-icon>
          YOLO Detection
        </el-tag>
      </template>
    </el-page-header>

    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col :span="10">
        <el-card class="control-panel">
          <template #header>
            <div class="card-header">
              <span>检测参数</span>
            </div>
          </template>

          <el-form :model="form" label-width="100px">
            <el-form-item label="检测模型">
              <el-select v-model="selectedModelId" placeholder="选择检测模型" style="width: 100%;">
                <el-option-group label="内置模型">
                  <el-option
                    v-for="m in allModels.filter(x => x.id === 'builtin')"
                    :key="m.id"
                    :label="m.modelName"
                    :value="m.id"
                  />
                </el-option-group>
                <el-option-group v-if="allModels.filter(x => x.id !== 'builtin').length > 0" label="自定义训练模型">
                  <el-option
                    v-for="m in allModels.filter(x => x.id !== 'builtin')"
                    :key="m.id"
                    :value="m.id"
                  >
                    <span>{{ m.modelName }}</span>
                    <span style="float: right; color: #8492a6; font-size: 12px;">
                      {{ m.classes.length }}类
                    </span>
                  </el-option>
                </el-option-group>
              </el-select>
            </el-form-item>

            <el-form-item label="检测类别">
              <el-select v-model="form.classId" placeholder="选择检测类别" style="width: 100%;">
                <el-option
                  v-for="cls in currentClasses"
                  :key="cls.classId"
                  :label="cls.cnName || cls.className"
                  :value="cls.classId"
                >
                  <span style="float: left">{{ cls.cnName || cls.className }}</span>
                  <span style="float: right; color: #8492a6; font-size: 13px">{{ cls.className }}</span>
                </el-option>
              </el-select>
            </el-form-item>

            <el-form-item label="置信度阈值">
              <el-slider
                v-model="form.confidenceThreshold"
                :min="0"
                :max="1"
                :step="0.05"
                :format-tooltip="formatTooltip"
                show-input
              />
            </el-form-item>

            <el-form-item label="IOU阈值">
              <el-slider
                v-model="form.iouThreshold"
                :min="0"
                :max="1"
                :step="0.05"
                :format-tooltip="formatTooltip"
                show-input
              />
            </el-form-item>

            <el-form-item label="上传图片">
              <el-upload
                ref="uploadRef"
                :auto-upload="false"
                :on-change="handleFileChange"
                :limit="1"
                :on-exceed="handleExceed"
                accept="image/*"
                drag
                style="width: 100%;"
              >
                <el-icon class="el-icon--upload"><upload-filled /></el-icon>
                <div class="el-upload__text">
                  拖拽图片到此处或 <em>点击上传</em>
                </div>
                <template #tip>
                  <div class="el-upload__tip">
                    只能上传 JPG/PNG 图片，且不超过 10MB
                  </div>
                </template>
              </el-upload>
            </el-form-item>

            <el-form-item>
              <el-button
                type="primary"
                :loading="detecting"
                @click="handleDetect"
                style="width: 100%;"
              >
                <el-icon><Search /></el-icon>
                开始检测
              </el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card class="result-panel">
          <template #header>
            <div class="card-header">
              <span>检测结果</span>
              <el-tag v-if="detectionResult" type="success">
                检测到 {{ detectionResult.detections?.length || 0 }} 个目标
              </el-tag>
            </div>
          </template>

          <div v-if="!detectionResult" class="empty-state">
            <el-empty description="请上传图片并点击检测按钮" />
          </div>

          <div v-else class="result-content">
            <div class="image-container">
              <el-image
                :src="`data:image/jpeg;base64,${detectionResult.image_base64}`"
                fit="contain"
                style="width: 100%; max-height: 500px;"
              />
            </div>

            <div v-if="detectionResult.detections && detectionResult.detections.length > 0" class="detections-table">
              <el-divider content-position="left">检测详情</el-divider>
              <el-table :data="detectionResult.detections" border stripe style="width: 100%;">
                <el-table-column type="index" label="序号" width="60" />
                <el-table-column prop="class" label="类别" width="120" />
                <el-table-column prop="confidence" label="置信度" width="100">
                  <template #default="{ row }">
                    <el-tag :type="getConfidenceType(row.confidence)">
                      {{ (row.confidence * 100).toFixed(1) }}%
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="边界框 (归一化)" width="200">
                  <template #default="{ row }">
                    <span>[{{ row.bbox.map(v => v.toFixed(3)).join(', ') }}]</span>
                  </template>
                </el-table-column>
                <el-table-column label="边界框 (像素)" width="200">
                  <template #default="{ row }">
                    <span>[{{ row.bbox_absolute.map(v => v.toFixed(0)).join(', ') }}]</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Monitor, Search, UploadFilled } from '@element-plus/icons-vue'
import axios from 'axios'
import { getAvailableModels } from '@/api/customModel'

const router = useRouter()
const route = useRoute()

const form = reactive({
  classId: 3,
  confidenceThreshold: 0.5,
  iouThreshold: 0.45
})

const allModels = ref([])
const selectedModelId = ref('builtin')
const detecting = ref(false)
const detectionResult = ref(null)
const selectedFile = ref(null)

const currentClasses = computed(() => {
  const model = allModels.value.find(m => m.id === selectedModelId.value)
  return model ? model.classes : []
})

const currentModelPath = computed(() => {
  const model = allModels.value.find(m => m.id === selectedModelId.value)
  return model ? model.modelPath : ''
})

const formatTooltip = (val) => {
  return (val * 100).toFixed(0) + '%'
}

const getConfidenceType = (conf) => {
  if (conf >= 0.8) return 'success'
  if (conf >= 0.6) return 'warning'
  return 'danger'
}

const handleFileChange = (file) => {
  selectedFile.value = file.raw
}

const handleExceed = () => {
  ElMessage.warning('只能上传一张图片')
}

const handleDetect = async () => {
  if (!selectedFile.value) {
    ElMessage.error('请先上传图片')
    return
  }

  if (!currentModelPath.value) {
    ElMessage.warning('请先选择检测模型')
    return
  }

  detecting.value = true
  detectionResult.value = null

  try {
    const formData = new FormData()
    formData.append('image', selectedFile.value)
    formData.append('class_id', form.classId)
    formData.append('model_path', currentModelPath.value)
    formData.append('confidence_threshold', form.confidenceThreshold)
    formData.append('iou_threshold', form.iouThreshold)

    const response = await axios.post('/api/v1/detection/single-class', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })

    if (response.data.success) {
      detectionResult.value = response.data.data
      ElMessage.success('检测完成')
    } else {
      ElMessage.error('检测失败: ' + response.data.message)
    }
  } catch (error) {
    console.error('Detection error:', error)
    ElMessage.error('检测失败: ' + (error.response?.data?.message || error.message))
  } finally {
    detecting.value = false
  }
}

const loadAllModels = async () => {
  allModels.value = []

  try {
    const res = await axios.get('/api/v1/detection/model-info')
    const data = res.data?.data || res.data
    if (data && data.classes) {
      allModels.value.push({
        id: 'builtin',
        modelName: '内置 VisDrone 检测模型（10类）',
        modelPath: data.model_path || '/root/autodl-fs/xingmu_jiancepingtai/runs/detect/train7/weights/best.pt',
        classes: data.classes.map(c => ({
          classId: c.class_id,
          className: c.name,
          cnName: c.cn_name || c.name
        }))
      })
    }
  } catch (e) {
    console.warn('加载内置模型信息失败:', e)
    allModels.value.push({
      id: 'builtin',
      modelName: '内置 VisDrone 检测模型',
      modelPath: '/root/autodl-fs/xingmu_jiancepingtai/runs/detect/train7/weights/best.pt',
      classes: []
    })
  }

  try {
    const res = await getAvailableModels()
    const customList = res.data?.data || res.data || []
    for (const m of customList) {
      allModels.value.push({
        id: `custom_${m.id}`,
        modelName: m.modelName,
        modelPath: m.modelPath,
        classes: (m.classes || []).map(c => ({
          classId: c.classId,
          className: c.className,
          cnName: c.cnName || c.className
        }))
      })
    }
  } catch (e) {
    console.warn('加载自定义模型失败:', e)
  }

  if (route.query.modelId) {
    const targetId = `custom_${route.query.modelId}`
    if (allModels.value.find(m => m.id === targetId)) {
      selectedModelId.value = targetId
    }
  } else {
    selectedModelId.value = 'builtin'
  }
}

watch(selectedModelId, () => {
  if (currentClasses.value.length > 0) {
    form.classId = currentClasses.value[0].classId
  }
})

const goBack = () => {
  router.back()
}

onMounted(() => {
  loadAllModels()
})
</script>

<style scoped>
.single-class-detection {
  padding: 20px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  min-height: 100vh;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}

.control-panel,
.result-panel {
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
}

.empty-state {
  padding: 60px 0;
}

.result-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.image-container {
  background: #f5f7fa;
  border-radius: 8px;
  padding: 10px;
  display: flex;
  justify-content: center;
  align-items: center;
}

.detections-table {
  margin-top: 10px;
}

:deep(.el-upload-dragger) {
  padding: 20px;
}

:deep(.el-icon--upload) {
  font-size: 48px;
  color: #667eea;
}

:deep(.el-slider__runway) {
  background-color: #e4e7ed;
}

:deep(.el-slider__bar) {
  background-color: #667eea;
}

:deep(.el-slider__button) {
  border-color: #667eea;
}
</style>
