<template>
  <div class="create-assessment">
    <div class="page-header">
      <div>
        <h1>新建可行性评估</h1>
        <p>填写需求、样本条件和资源约束后生成评估工作流。</p>
      </div>
    </div>

    <el-card shadow="never">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-section">
          <h2>基础信息</h2>
          <el-row :gutter="16">
            <el-col :xs="24" :md="12">
              <el-form-item label="评估名称" prop="assessmentName">
                <el-input v-model="form.assessmentName" maxlength="100" show-word-limit />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-form-item label="类别数量" prop="categoryCount">
                <el-input-number v-model="form.categoryCount" :min="1" :max="200" style="width: 100%;" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item label="需求描述" prop="rawRequirement">
            <el-input
              v-model="form.rawRequirement"
              type="textarea"
              :rows="6"
              maxlength="2000"
              show-word-limit
              placeholder="描述目标类别、场景、相机视角、部署环境和验收要求"
            />
          </el-form-item>
        </div>

        <div class="form-section">
          <h2>数据与标注</h2>
          <el-row :gutter="16">
            <el-col :xs="24" :sm="12" :md="6">
              <el-form-item label="数据量" prop="datasetSize">
                <el-input-number v-model="form.datasetSize" :min="0" :step="100" style="width: 100%;" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12" :md="6">
              <el-form-item label="单类别样本数" prop="samplesPerCategory">
                <el-input-number v-model="form.samplesPerCategory" :min="0" :step="50" style="width: 100%;" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12" :md="6">
              <el-form-item label="图片质量" prop="imageQuality">
                <el-select v-model="form.imageQuality" style="width: 100%;">
                  <el-option label="高" value="HIGH" />
                  <el-option label="中" value="MEDIUM" />
                  <el-option label="低" value="LOW" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12" :md="6">
              <el-form-item label="标注完整度" prop="annotationCompleteness">
                <el-input-number v-model="form.annotationCompleteness" :min="0" :max="100" style="width: 100%;" />
              </el-form-item>
            </el-col>
          </el-row>
        </div>

        <div class="form-section">
          <h2>检测难度</h2>
          <el-row :gutter="16">
            <el-col :xs="24" :sm="12" :md="4">
              <el-form-item label="目标尺寸">
                <el-select v-model="form.targetSize" style="width: 100%;">
                  <el-option label="大" value="LARGE" />
                  <el-option label="中" value="MEDIUM" />
                  <el-option label="小" value="SMALL" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12" :md="5">
              <el-form-item label="背景复杂度">
                <el-select v-model="form.backgroundComplexity" style="width: 100%;">
                  <el-option label="低" value="LOW" />
                  <el-option label="中" value="MEDIUM" />
                  <el-option label="高" value="HIGH" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12" :md="5">
              <el-form-item label="类间相似度">
                <el-select v-model="form.interClassSimilarity" style="width: 100%;">
                  <el-option label="低" value="LOW" />
                  <el-option label="中" value="MEDIUM" />
                  <el-option label="高" value="HIGH" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12" :md="5">
              <el-form-item label="预期精度" prop="expectedAccuracy">
                <el-input-number v-model="form.expectedAccuracy" :min="1" :max="100" style="width: 100%;" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12" :md="5">
              <el-form-item label="时间预算" prop="timeBudgetDays">
                <el-input-number v-model="form.timeBudgetDays" :min="1" :step="7" style="width: 100%;" />
              </el-form-item>
            </el-col>
          </el-row>
        </div>

        <div class="form-section">
          <h2>资源与参考图</h2>
          <el-row :gutter="16">
            <el-col :xs="24" :md="10">
              <el-form-item label="训练资源">
                <el-select v-model="form.trainingResource" style="width: 100%;">
                  <el-option label="无 GPU / 仅评估" value="NONE" />
                  <el-option label="单卡消费级 GPU" value="SINGLE_CONSUMER_GPU" />
                  <el-option label="单卡专业 GPU" value="SINGLE_PRO_GPU" />
                  <el-option label="多卡 GPU" value="MULTI_GPU" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :md="14">
              <el-form-item label="参考图片">
                <el-upload
                  v-model:file-list="fileList"
                  :action="uploadAction"
                  :headers="uploadHeaders"
                  list-type="picture-card"
                  accept="image/jpeg,image/png,image/webp"
                  :limit="10"
                  :on-exceed="handleExceed"
                  :on-success="handleUploadSuccess"
                  :on-error="handleUploadError"
                  :on-remove="handleUploadRemove"
                  :before-upload="beforeUpload"
                >
                  <el-icon><Plus /></el-icon>
                </el-upload>
                <div class="upload-tip">最多10张，单张不超过5MB</div>
              </el-form-item>
            </el-col>
          </el-row>
        </div>

        <div class="form-actions">
          <el-button type="primary" size="large" :loading="submitting" @click="handleSubmit">创建评估</el-button>
          <el-button size="large" @click="handleCancel">取消</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { feasibilityAPI } from '@/api/feasibility'

const router = useRouter()
const formRef = ref(null)
const submitting = ref(false)
const fileList = ref([])

const form = reactive({
  assessmentName: '',
  rawRequirement: '',
  imageUrls: [],
  datasetSize: 1000,
  categoryCount: 1,
  samplesPerCategory: 300,
  imageQuality: 'MEDIUM',
  annotationCompleteness: 80,
  targetSize: 'MEDIUM',
  backgroundComplexity: 'MEDIUM',
  interClassSimilarity: 'MEDIUM',
  expectedAccuracy: 85,
  trainingResource: 'SINGLE_CONSUMER_GPU',
  timeBudgetDays: 30
})

const rules = {
  assessmentName: [
    { required: true, message: '请输入评估名称', trigger: 'blur' },
    { min: 3, max: 100, message: '长度在 3 到 100 个字符', trigger: 'blur' }
  ],
  rawRequirement: [
    { required: true, message: '请输入需求描述', trigger: 'blur' },
    { min: 10, max: 2000, message: '长度在 10 到 2000 个字符', trigger: 'blur' }
  ],
  datasetSize: [{ required: true, type: 'number', min: 0, message: '数据量不能为负数', trigger: 'change' }],
  categoryCount: [{ required: true, type: 'number', min: 1, max: 200, message: '类别数量范围 1-200', trigger: 'change' }],
  samplesPerCategory: [{ required: true, type: 'number', min: 0, message: '样本数不能为负数', trigger: 'change' }],
  annotationCompleteness: [{ required: true, type: 'number', min: 0, max: 100, message: '范围 0-100', trigger: 'change' }],
  expectedAccuracy: [{ required: true, type: 'number', min: 1, max: 100, message: '范围 1-100', trigger: 'change' }],
  timeBudgetDays: [{ required: true, type: 'number', min: 1, message: '至少 1 天', trigger: 'change' }]
}

const uploadAction = computed(() => `${import.meta.env.VITE_API_BASE_URL || ''}/api/v1/upload/image`)
const uploadHeaders = computed(() => ({ Authorization: `Bearer ${localStorage.getItem('token')}` }))

function handleExceed() {
  ElMessage.warning('最多只能上传10张图片')
}

function beforeUpload(file) {
  const allowed = ['image/jpeg', 'image/png', 'image/webp']
  if (!allowed.includes(file.type)) {
    ElMessage.error('仅支持 JPG、PNG、WEBP 图片')
    return false
  }
  if (file.size / 1024 / 1024 >= 5) {
    ElMessage.error('图片大小不能超过 5MB')
    return false
  }
  return true
}

function handleUploadSuccess(response, file) {
  if (response.success && response.data) {
    const path = response.data.url || response.data.path
    file.responsePath = path
    if (path && !form.imageUrls.includes(path)) {
      form.imageUrls.push(path)
    }
  }
}

function handleUploadRemove(file) {
  const path = file.responsePath || file.response?.data?.url || file.response?.data?.path
  if (path) {
    form.imageUrls = form.imageUrls.filter(item => item !== path)
  }
}

function handleUploadError() {
  ElMessage.error('图片上传失败')
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const response = await feasibilityAPI.createAssessment({ ...form })
    ElMessage.success('创建成功')
    router.push(`/feasibility/${response.data.id}`)
  } catch (error) {
    console.error('创建失败:', error)
  } finally {
    submitting.value = false
  }
}

function handleCancel() {
  router.back()
}
</script>

<style scoped>
.create-assessment {
  max-width: 1120px;
}

.page-header {
  margin-bottom: 18px;
}

.page-header h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--gray-900);
}

.page-header p {
  margin: 6px 0 0;
  color: var(--gray-500);
  font-size: 13px;
}

.form-section {
  padding: 4px 0 12px;
  border-bottom: 1px solid var(--gray-200);
  margin-bottom: 18px;
}

.form-section h2 {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
  color: var(--gray-800);
}

.upload-tip {
  margin-top: 6px;
  font-size: 12px;
  color: var(--gray-500);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

:deep(.el-upload-list--picture-card .el-upload-list__item),
:deep(.el-upload--picture-card) {
  width: 100px;
  height: 100px;
}
</style>
