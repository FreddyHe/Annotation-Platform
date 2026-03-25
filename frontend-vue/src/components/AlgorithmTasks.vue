<template>
  <div class="auto-annotation">
    <p class="section-desc">通过 Grounding DINO 检测 + VLM 智能清洗，实现全自动标注流程</p>

    <el-card v-if="project.labels && project.labels.length > 0" class="panel">
      <template #header><span class="card-title">项目信息</span></template>
      <div class="info-row">
        <div class="info-item"><span class="info-label">项目名称</span><span class="info-value">{{ project.name }}</span></div>
        <div class="info-item"><span class="info-label">图片数量</span><span class="info-value">{{ project.totalImages || 0 }}</span></div>
        <div class="info-item"><span class="info-label">已处理图片</span><span class="info-value">{{ project.processedImages || 0 }}</span></div>
      </div>
    </el-card>

    <el-card class="panel">
      <template #header><span class="card-title">类别定义</span></template>
      <p class="labels-tip">
        将使用本项目 <strong>{{ Array.isArray(project.labels) ? project.labels.length : 0 }}</strong> 个类别进行自动化标注与智能清洗：
      </p>
      <div class="labels-list" v-if="Array.isArray(project.labels) && project.labels.length > 0">
        <el-tag v-for="(label, index) in project.labels" :key="index" size="default" style="margin-right: 8px; margin-bottom: 8px;">
          {{ label }}
        </el-tag>
      </div>
      <el-alert v-else title="未定义类别" type="warning" :closable="false" />
    </el-card>

    <el-card class="panel">
      <template #header><span class="card-title">参数配置</span></template>
      <div class="config-item">
        <el-switch v-model="enableVlmCleaning" :disabled="isButtonDisabled" active-text="开启 VLM 智能清洗" inactive-text="关闭 VLM 智能清洗" />
        <span class="config-hint">开启后将使用 VLM 模型对 DINO 检测结果进行智能清洗和验证</span>
      </div>
      <div class="config-item" style="margin-top: 16px;">
        <el-radio-group v-model="processRange" :disabled="isButtonDisabled">
          <el-radio label="all">全部图片</el-radio>
          <el-radio label="unprocessed">未处理图片</el-radio>
        </el-radio-group>
      </div>
    </el-card>

    <el-card class="panel">
      <template #header><span class="card-title">操作</span></template>
      <div class="action-center">
        <el-button 
          type="primary" 
          size="large" 
          :icon="VideoPlay" 
          :loading="isStarting" 
          :disabled="isButtonDisabled"
          @click="startAutoAnnotation"
        >
          {{ buttonText }}
        </el-button>
        <p class="action-hint">{{ actionHint }}</p>
      </div>
    </el-card>

    <!-- Progress Section -->
    <el-card v-if="showProgress" class="panel progress-panel">
      <template #header>
        <div class="progress-header">
          <span class="card-title">标注进度</span>
          <el-tag v-if="isCompleted" type="success" size="small">已完成</el-tag>
          <el-tag v-else-if="isFailed" type="danger" size="small">失败</el-tag>
          <el-tag v-else type="primary" size="small">进行中</el-tag>
        </div>
      </template>
      
      <div class="progress-content">
        <div class="progress-info">
          <span class="progress-label">{{ currentStepText }}</span>
          <span class="progress-percent">{{ progressPercent }}%</span>
        </div>
        <el-progress 
          :percentage="progressPercent" 
          :status="isCompleted ? 'success' : (isFailed ? 'exception' : undefined)"
          :stroke-width="12"
        />
        
        <div v-if="isCompleted" class="completion-notice">
          <el-alert 
            title="自动标注已完成！" 
            type="success" 
            :closable="false"
            description="请前往 Label Studio 查看标注结果。"
          />
        </div>

        <div v-if="isFailed" class="error-notice">
          <el-alert 
            title="标注过程出现错误" 
            type="error" 
            :closable="false"
            description="请查看后端日志获取详细错误信息。"
          />
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VideoPlay } from '@element-plus/icons-vue'
import { autoAnnotationAPI } from '@/api'

const props = defineProps({ project: { type: Object, required: true } })
const emit = defineEmits(['refresh'])

const isStarting = ref(false)
const enableVlmCleaning = ref(true)
const processRange = ref('all')

// 基于 project.status 计算按钮是否禁用
const isButtonDisabled = computed(() => {
  const processingStatuses = ['DETECTING', 'CLEANING', 'SYNCING', 'UPLOADING']
  return processingStatuses.includes(props.project.status) || isStarting.value
})

// 基于 project.status 计算按钮文字
const buttonText = computed(() => {
  switch (props.project.status) {
    case 'DETECTING': return 'DINO检测中...'
    case 'CLEANING': return 'VLM清洗中...'
    case 'SYNCING': return '同步到Label Studio中...'
    case 'COMPLETED': return '重新执行自动标注'
    case 'FAILED': return '重新执行自动标注'
    default: return '启动一键自动标注'
  }
})

// 基于 project.status 计算提示文字
const actionHint = computed(() => {
  const processingStatuses = ['DETECTING', 'CLEANING', 'SYNCING', 'UPLOADING']
  if (processingStatuses.includes(props.project.status)) {
    return '标注正在后台执行，请耐心等待...'
  }
  if (props.project.status === 'COMPLETED') {
    return '上次标注已完成，可以重新执行'
  }
  return '点击按钮开始全自动标注流程（DINO 检测 + VLM 清洗）'
})

// 是否显示进度卡片
const showProgress = computed(() => {
  return ['DETECTING', 'CLEANING', 'SYNCING', 'COMPLETED', 'FAILED'].includes(props.project.status)
})

// 进度百分比
const progressPercent = computed(() => {
  const map = { 
    'UPLOADING': 10,
    'DETECTING': 30, 
    'CLEANING': 60, 
    'SYNCING': 85, 
    'COMPLETED': 100, 
    'FAILED': 0 
  }
  return map[props.project.status] || 0
})

// 当前步骤文字
const currentStepText = computed(() => {
  const map = {
    'UPLOADING': '上传图片中...',
    'DETECTING': 'DINO 检测中...',
    'CLEANING': 'VLM 智能清洗中...',
    'SYNCING': '同步到 Label Studio...',
    'COMPLETED': '自动标注已完成！',
    'FAILED': '标注失败'
  }
  return map[props.project.status] || ''
})

// 是否已完成
const isCompleted = computed(() => props.project.status === 'COMPLETED')

// 是否失败
const isFailed = computed(() => props.project.status === 'FAILED')

const startAutoAnnotation = async () => {
  try {
    isStarting.value = true
    await ElMessageBox.confirm(
      `即将启动一键自动标注流程：\n- 项目：${props.project.name}\n- 类别数：${props.project.labels?.length || 0}\n- 范围：${processRange.value === 'all' ? '全部图片' : '未处理图片'}\n- VLM 清洗：${enableVlmCleaning.value ? '开启' : '关闭'}\n\n此操作将在后台执行，请勿关闭页面。`,
      '确认启动', 
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning', distinguishCancelAndClose: true }
    )
    
    await autoAnnotationAPI.startAutoAnnotation(props.project.id, { processRange: processRange.value })
    
    ElMessage.success({ message: '自动标注已启动！', duration: 3000 })
    
    // 触发父组件刷新项目数据，这样 project.status 会更新
    emit('refresh')
    
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('启动自动标注失败：' + (error.message || '未知错误'))
    }
  } finally {
    isStarting.value = false
  }
}
</script>

<style scoped>
.auto-annotation { }
.section-desc { color: var(--gray-500); font-size: 13px; margin-bottom: 20px; }
.panel { margin-bottom: 16px; }
.card-title { font-size: 14px; font-weight: 500; color: var(--gray-900); }
.info-row { display: flex; flex-wrap: wrap; gap: 24px; }
.info-item { display: flex; flex-direction: column; min-width: 120px; }
.info-label { font-size: 12px; color: var(--gray-400); margin-bottom: 4px; }
.info-value { font-size: 16px; font-weight: 600; color: var(--gray-900); }
.labels-tip { margin: 0 0 12px; font-size: 13px; color: var(--gray-600); }
.labels-list { display: flex; flex-wrap: wrap; }
.config-item { margin-bottom: 4px; }
.config-hint { display: block; font-size: 12px; color: var(--gray-400); margin-top: 6px; }
.action-center { text-align: center; padding: 12px 0; }
.action-hint { margin: 12px 0 0; font-size: 12px; color: var(--gray-400); }

.progress-panel { }
.progress-header { display: flex; justify-content: space-between; align-items: center; }
.progress-content { }
.progress-info { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.progress-label { font-size: 14px; color: var(--gray-700); font-weight: 500; }
.progress-percent { font-size: 18px; color: var(--brand-600); font-weight: 600; }
.completion-notice { margin-top: 20px; }
.error-notice { margin-top: 20px; }
</style>
