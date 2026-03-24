<template>
  <div class="create-assessment">
    <div class="page-title">新建可行性评估</div>
    <el-form ref="formRef" :model="form" :rules="rules" label-width="120px" style="max-width: 800px">
      <el-form-item label="评估名称" prop="assessmentName"><el-input v-model="form.assessmentName" placeholder="请输入评估名称" maxlength="100" show-word-limit /></el-form-item>
      <el-form-item label="需求描述" prop="rawRequirement"><el-input v-model="form.rawRequirement" type="textarea" :rows="8" placeholder="请详细描述您的检测需求" maxlength="2000" show-word-limit /></el-form-item>
      <el-form-item label="参考图片">
        <el-upload v-model:file-list="fileList" :action="uploadAction" :headers="uploadHeaders" list-type="picture-card" accept="image/*" :limit="10" :on-exceed="handleExceed" :on-success="handleUploadSuccess" :on-error="handleUploadError" :before-upload="beforeUpload"><el-icon><Plus /></el-icon></el-upload>
        <div class="upload-tip">最多上传10张图片，支持jpg、png格式</div>
      </el-form-item>
      <el-form-item><el-button type="primary" @click="handleSubmit" :loading="submitting">创建评估</el-button><el-button @click="handleCancel">取消</el-button></el-form-item>
    </el-form>
  </div>
</template>
<script setup>
import { ref, reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { feasibilityAPI } from '@/api/feasibility'
const router = useRouter(); const formRef = ref(null); const submitting = ref(false); const fileList = ref([])
const form = reactive({ assessmentName: '', rawRequirement: '', imageUrls: [] })
const rules = { assessmentName: [{ required: true, message: '请输入评估名称', trigger: 'blur' }, { min: 2, max: 100, message: '长度在 2 到 100 个字符', trigger: 'blur' }], rawRequirement: [{ required: true, message: '请输入需求描述', trigger: 'blur' }, { min: 10, max: 2000, message: '长度在 10 到 2000 个字符', trigger: 'blur' }] }
const uploadAction = computed(() => (import.meta.env.VITE_API_BASE_URL || '') + '/api/v1/upload/image')
const uploadHeaders = computed(() => ({ Authorization: 'Bearer ' + localStorage.getItem('token') }))
const handleExceed = () => { ElMessage.warning('最多只能上传10张图片') }
const beforeUpload = (file) => { if (!file.type.startsWith('image/')) { ElMessage.error('只能上传图片文件!'); return false }; if (file.size / 1024 / 1024 >= 5) { ElMessage.error('图片大小不能超过 5MB!'); return false }; return true }
const handleUploadSuccess = (response) => { if (response.success && response.data) form.imageUrls.push(response.data.url || response.data.path) }
const handleUploadError = () => { ElMessage.error('图片上传失败') }
const handleSubmit = async () => { if (!formRef.value) return; try { await formRef.value.validate(); submitting.value = true; const res = await feasibilityAPI.createAssessment({ assessmentName: form.assessmentName, rawRequirement: form.rawRequirement, imageUrls: form.imageUrls }); ElMessage.success('创建成功'); router.push('/feasibility/' + res.data.id) } catch (error) { if (error !== false) console.error('创建失败:', error) } finally { submitting.value = false } }
const handleCancel = () => { router.back() }
</script>
<style scoped>
.create-assessment { max-width: 900px; }
.page-title { font-size: 20px; font-weight: 600; color: var(--gray-900); letter-spacing: -0.02em; margin-bottom: 24px; }
.upload-tip { margin-top: 6px; font-size: 12px; color: var(--gray-400); }
:deep(.el-upload-list--picture-card .el-upload-list__item) { width: 100px; height: 100px; }
:deep(.el-upload--picture-card) { width: 100px; height: 100px; }
</style>
