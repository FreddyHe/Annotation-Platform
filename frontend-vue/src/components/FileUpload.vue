<template>
  <div class="file-upload">
    <p class="section-desc">支持大文件分块上传，自动断点续传，上传完成后自动合并</p>
    <el-upload ref="uploadRef" class="upload-demo" drag :auto-upload="false" :on-change="handleFileChange" :on-remove="handleFileRemove" :file-list="fileList" :limit="10" multiple>
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">将文件拖到此处，或<em>点击上传</em></div>
      <template #tip><div class="el-upload__tip">支持 JPG、PNG、ZIP、MP4 等格式，单个文件最大 10GB</div></template>
    </el-upload>
    <div v-if="fileList.length > 0" class="upload-actions">
      <el-button type="primary" size="large" @click="startUpload" :loading="uploading"><el-icon><VideoPlay /></el-icon>开始上传</el-button>
      <el-button size="large" @click="clearFiles"><el-icon><Delete /></el-icon>清空列表</el-button>
    </div>
    <div v-if="uploading" class="upload-progress">
      <div class="progress-section">
        <div class="progress-header"><span>{{ currentFileName }}</span><span>{{ currentChunk }}/{{ totalChunks }} 块 ({{ chunkProgress }}%)</span></div>
        <el-progress :percentage="chunkProgress" :status="uploadStatus" :stroke-width="12" />
      </div>
      <div class="progress-section" v-if="totalChunks > 1">
        <div class="progress-header"><span>总体进度</span><span>{{ uploadedCount }}/{{ totalCount }} 文件</span></div>
        <el-progress :percentage="totalProgress" :stroke-width="8" status="success" />
      </div>
    </div>
    <div v-if="uploadResults.length > 0" class="upload-results">
      <div class="section-label">上传结果</div>
      <el-table :data="uploadResults" style="width: 100%">
        <el-table-column prop="fileName" label="文件名" />
        <el-table-column prop="fileSize" label="大小" width="120"><template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template></el-table-column>
        <el-table-column prop="status" label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'success' ? 'success' : 'danger'" size="small">{{ row.status === 'success' ? '成功' : '失败' }}</el-tag></template></el-table-column>
        <el-table-column prop="message" label="信息" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { uploadAPI } from '@/api'
import { ElMessage } from 'element-plus'

const props = defineProps({ projectId: { type: [String, Number], required: true } })
const emit = defineEmits(['uploaded'])
const uploadRef = ref(null)
const fileList = ref([])
const uploading = ref(false)
const currentFileName = ref('')
const uploadedCount = ref(0)
const totalCount = ref(0)
const uploadResults = ref([])
const currentChunk = ref(0)
const totalChunks = ref(0)
const chunkProgress = ref(0)
const CHUNK_SIZE = 5 * 1024 * 1024
const MAX_RETRIES = 3

const totalProgress = computed(() => { if (totalCount.value === 0) return 0; return Math.round((uploadedCount.value / totalCount.value) * 100) })
const uploadStatus = computed(() => { if (uploadResults.value.some(r => r.status === 'error')) return 'exception'; return undefined })
const formatFileSize = (bytes) => { if (bytes === 0) return '0 B'; const k = 1024; const sizes = ['B', 'KB', 'MB', 'GB']; const i = Math.floor(Math.log(bytes) / Math.log(k)); return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i] }
const handleFileChange = (file, files) => { fileList.value = files }
const handleFileRemove = (file, files) => { fileList.value = files }
const clearFiles = () => { fileList.value = []; uploadResults.value = []; uploadedCount.value = 0; totalCount.value = 0; currentChunk.value = 0; totalChunks.value = 0; chunkProgress.value = 0 }

const startUpload = async () => {
  if (fileList.value.length === 0) { ElMessage.warning('请先选择文件'); return }
  uploading.value = true; uploadResults.value = []; uploadedCount.value = 0; totalCount.value = fileList.value.length
  for (let i = 0; i < fileList.value.length; i++) {
    const file = fileList.value[i]; currentFileName.value = file.name
    try { await uploadFile(file.raw); uploadResults.value.push({ fileName: file.name, fileSize: file.size, status: 'success', message: '上传成功' }); ElMessage.success(`${file.name} 上传成功`) }
    catch (error) { uploadResults.value.push({ fileName: file.name, fileSize: file.size, status: 'error', message: error.message || '上传失败' }); ElMessage.error(`${file.name} 上传失败：${error.message || '未知错误'}`) }
    uploadedCount.value++
  }
  uploading.value = false; currentFileName.value = ''; currentChunk.value = 0; totalChunks.value = 0; chunkProgress.value = 0; emit('uploaded')
}

const makeFileId = async (file, projectId) => {
  const raw = `${projectId}::${file.name}::${file.size}::${file.lastModified}`
  const buf = new TextEncoder().encode(raw)
  const hash = await crypto.subtle.digest('SHA-256', buf)
  return Array.from(new Uint8Array(hash)).map(b => b.toString(16).padStart(2, '0')).join('').slice(0, 32)
}

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))

const uploadChunkWithRetry = async (formData, maxRetries = MAX_RETRIES) => {
  let lastErr
  for (let i = 0; i < maxRetries; i++) {
    try {
      return await uploadAPI.uploadChunk(formData)
    } catch (error) {
      lastErr = error
      if (i < maxRetries - 1) {
        await sleep(1000 * Math.pow(2, i))
      }
    }
  }
  throw lastErr
}

const getUploadedChunkSet = async (fileId) => {
  try {
    const res = await uploadAPI.getUploadedChunks(fileId)
    return new Set(res.data?.uploadedChunks || [])
  } catch {
    return new Set()
  }
}

const uploadFile = async (file) => {
  const fileId = await makeFileId(file, props.projectId); const fileName = file.name; const fileSize = file.size; const totalChunksCount = Math.ceil(fileSize / CHUNK_SIZE)
  totalChunks.value = totalChunksCount; currentChunk.value = 0; chunkProgress.value = 0
  const uploadedChunks = await getUploadedChunkSet(fileId)
  for (let chunkIndex = 0; chunkIndex < totalChunksCount; chunkIndex++) {
    if (uploadedChunks.has(chunkIndex)) {
      currentChunk.value = chunkIndex + 1; chunkProgress.value = Math.round(((uploadedChunks.size) / totalChunksCount) * 100)
      continue
    }
    const start = chunkIndex * CHUNK_SIZE; const end = Math.min(start + CHUNK_SIZE, fileSize); const chunk = file.slice(start, end)
    const formData = new FormData(); formData.append('fileId', fileId); formData.append('filename', fileName); formData.append('chunkIndex', chunkIndex); formData.append('totalChunks', totalChunksCount); formData.append('fileSize', fileSize); formData.append('projectId', props.projectId); formData.append('file', chunk)
    currentChunk.value = chunkIndex + 1
    await uploadChunkWithRetry(formData)
    uploadedChunks.add(chunkIndex)
    chunkProgress.value = Math.round((uploadedChunks.size / totalChunksCount) * 100)
  }
  await uploadAPI.mergeChunks({ fileId, filename: fileName, totalChunks: totalChunksCount, projectId: props.projectId })
}
</script>

<style scoped>
.file-upload { }
.section-desc { color: var(--gray-500); font-size: 13px; margin-bottom: 16px; }
.upload-actions { margin-top: 20px; text-align: center; display: flex; justify-content: center; gap: 12px; }
.upload-progress { margin-top: 20px; padding: 16px; background: var(--gray-50); border-radius: var(--radius-md); }
.progress-section { margin-bottom: 12px; }
.progress-section:last-child { margin-bottom: 0; }
.progress-header { display: flex; justify-content: space-between; margin-bottom: 6px; font-size: 13px; color: var(--gray-600); }
.upload-results { margin-top: 24px; }
.section-label { font-size: 13px; font-weight: 500; color: var(--gray-600); margin-bottom: 12px; padding-bottom: 8px; border-bottom: 0.5px solid var(--gray-200); }
</style>
