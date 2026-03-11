<template>
  <div class="video-extract">
    <el-alert
      title="视频抽帧"
      type="info"
      :closable="false"
      style="margin-bottom: 20px;"
    >
      <template #default>
        从视频文件中提取图片帧，支持自定义帧率和时间范围
      </template>
    </el-alert>

    <el-form :model="form" label-width="120px">
      <el-form-item label="选择视频">
        <el-select
          v-model="form.videoPath"
          placeholder="请选择视频文件"
          style="width: 100%;"
        >
          <el-option
            v-for="video in availableVideos"
            :key="video.path"
            :label="`${video.name} (${formatFileSize(video.size)})`"
            :value="video.path"
          />
        </el-select>
      </el-form-item>

      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="提取帧率">
            <el-select v-model="form.fps" style="width: 100%;">
              <el-option label="每 2 秒 1 帧" :value="0.5" />
              <el-option label="每秒 1 帧" :value="1" />
              <el-option label="每秒 2 帧" :value="2" />
              <el-option label="每秒 5 帧" :value="5" />
              <el-option label="每秒 10 帧" :value="10" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="输出格式">
            <el-select v-model="form.format" style="width: 100%;">
              <el-option label="JPG" value="jpg" />
              <el-option label="PNG" value="png" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="时间范围">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-input-number
              v-model="form.startTime"
              :min="0"
              :step="0.5"
              :precision="1"
              style="width: 100%;"
            />
            <span style="margin-left: 10px;">秒（开始）</span>
          </el-col>
          <el-col :span="12">
            <el-input-number
              v-model="form.endTime"
              :min="0"
              :step="0.5"
              :precision="1"
              style="width: 100%;"
            />
            <span style="margin-left: 10px;">秒（结束，0=到结尾）</span>
          </el-col>
        </el-row>
      </el-form-item>

      <el-form-item label="最大帧数">
        <el-input-number
          v-model="form.maxFrames"
          :min="0"
          :max="10000"
          style="width: 200px;"
        />
        <span style="margin-left: 10px;">（0=不限）</span>
      </el-form-item>

      <el-form-item label="图片质量">
        <el-slider
          v-model="form.quality"
          :min="50"
          :max="100"
          style="width: 300px;"
        />
        <span style="margin-left: 10px;">{{ form.quality }}%</span>
      </el-form-item>

      <el-form-item>
        <el-button
          type="primary"
          size="large"
          @click="handleExtract"
          :loading="extracting"
          :disabled="!form.videoPath"
        >
          <el-icon><VideoPlay /></el-icon>
          开始提取
        </el-button>
      </el-form-item>
    </el-form>

    <div v-if="extracting" class="extract-progress">
      <el-progress :percentage="extractProgress" />
      <div class="progress-info">
        <span>{{ currentStatus }}</span>
        <span>{{ extractedFrames }} / {{ totalFrames }} 帧</span>
      </div>
    </div>

    <div v-if="extractResults.length > 0" class="extract-results">
      <el-divider>提取结果</el-divider>
      <el-alert
        :title="`成功提取 ${extractResults.length} 帧图片`"
        type="success"
        :closable="false"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { uploadAPI } from '@/api'
import { ElMessage } from 'element-plus'

const props = defineProps({
  projectId: {
    type: [String, Number],
    required: true
  }
})

const emit = defineEmits(['extracted'])

const form = ref({
  videoPath: '',
  fps: 1,
  format: 'jpg',
  startTime: 0,
  endTime: 0,
  maxFrames: 0,
  quality: 95
})

const extracting = ref(false)
const extractProgress = ref(0)
const currentStatus = ref('')
const extractedFrames = ref(0)
const totalFrames = ref(0)
const extractResults = ref([])
const availableVideos = ref([])

const formatFileSize = (bytes) => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i]
}

const loadAvailableVideos = async () => {
  try {
    availableVideos.value = []
  } catch (error) {
    console.error('加载视频列表失败', error)
  }
}

const handleExtract = async () => {
  if (!form.value.videoPath) {
    ElMessage.warning('请先选择视频文件')
    return
  }

  try {
    if (!props.projectId || props.projectId === 'null') {
      return
    }
    
    extracting.value = true
    extractProgress.value = 0
    currentStatus.value = '正在初始化...'
    extractedFrames.value = 0
    totalFrames.value = 0
    extractResults.value = []

    const response = await uploadAPI.extractVideoFrames({
      projectId: props.projectId,
      videoPath: form.value.videoPath,
      fps: form.value.fps,
      format: form.value.format,
      startTime: form.value.startTime,
      endTime: form.value.endTime,
      maxFrames: form.value.maxFrames,
      quality: form.value.quality
    })

    extractResults.value = response.data.frames || []
    ElMessage.success(`成功提取 ${extractResults.value.length} 帧图片`)
    emit('extracted')
  } catch (error) {
    ElMessage.error('提取失败：' + (error.message || '未知错误'))
  } finally {
    extracting.value = false
  }
}

onMounted(() => {
  loadAvailableVideos()
})
</script>

<style scoped>
.video-extract {
  padding: 20px;
}

.extract-progress {
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

.extract-results {
  margin-top: 30px;
}
</style>
