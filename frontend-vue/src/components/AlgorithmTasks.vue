<template>
  <div class="auto-annotation">
    <el-alert
      title="一键自动标注"
      type="info"
      :closable="false"
      style="margin-bottom: 20px;"
    >
      <template #default>
        通过 Grounding DINO 检测 + VLM 智能清洗，实现全自动标注流程
      </template>
    </el-alert>

    <el-card v-if="project.labels && project.labels.length > 0" class="auto-annotation-panel">
      <template #header>
        <span class="card-title">📋 项目信息</span>
      </template>
      
      <div class="info-section">
        <div class="info-item">
          <span class="info-label">项目名称：</span>
          <span class="info-value">{{ project.name }}</span>
        </div>
        <div class="info-item">
          <span class="info-label">图片数量：</span>
          <span class="info-value">{{ project.totalImages || 0 }}</span>
        </div>
        <div class="info-item">
          <span class="info-label">已处理图片：</span>
          <span class="info-value">{{ project.processedImages || 0 }}</span>
        </div>
      </div>
    </el-card>

    <el-card class="auto-annotation-panel">
      <template #header>
        <span class="card-title">🎯 类别定义</span>
      </template>
      
      <div class="labels-section">
        <p class="labels-tip">
          将使用本项目 <strong>{{ Array.isArray(project.labels) ? project.labels.length : 0 }}</strong> 个类别进行自动化标注与智能清洗：
        </p>
        <div class="labels-list" v-if="Array.isArray(project.labels) && project.labels.length > 0">
          <el-tag
            v-for="(label, index) in project.labels"
            :key="index"
            type="info"
            size="large"
            style="margin-right: 10px; margin-bottom: 10px;"
          >
            {{ label }}
          </el-tag>
        </div>
        <div class="labels-empty" v-else>
          <el-alert title="未定义类别" type="warning" :closable="false" />
        </div>
      </div>
    </el-card>

    <el-card class="auto-annotation-panel">
      <template #header>
        <span class="card-title">⚙️ 参数配置</span>
      </template>
      
      <div class="config-section">
        <div class="config-item">
          <el-switch
            v-model="enableVlmCleaning"
            active-text="开启 VLM 智能清洗"
            inactive-text="关闭 VLM 智能清洗"
            style="--el-switch-on-color: #67c23a; --el-switch-off-color: #909399"
          />
          <span class="config-hint">
            开启后将使用 VLM 模型对 DINO 检测结果进行智能清洗和验证
          </span>
        </div>

        <div class="config-item">
          <el-radio-group v-model="processRange" style="margin-top: 15px;">
            <el-radio label="all" size="large">全部图片</el-radio>
            <el-radio label="unprocessed" size="large">未处理图片</el-radio>
          </el-radio-group>
          <span class="config-hint">
            选择需要处理的图片范围
          </span>
        </div>
      </div>
    </el-card>

    <el-card class="auto-annotation-panel">
      <template #header>
        <span class="card-title">🚀 操作</span>
      </template>
      
      <div class="action-section">
        <el-button
          type="primary"
          size="large"
          :icon="VideoPlay"
          :loading="isStarting"
          @click="startAutoAnnotation"
        >
          🚀 启动一键自动标注
        </el-button>
        <p class="action-hint">
          点击按钮开始全自动标注流程（DINO 检测 + VLM 清洗）
        </p>
      </div>
    </el-card>

    <el-card class="auto-annotation-panel">
      <template #header>
        <span class="card-title">📋 任务列表</span>
      </template>
      
      <TaskList :project-id="project.id" />
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VideoPlay } from '@element-plus/icons-vue'
import { autoAnnotationAPI } from '@/api'
import TaskList from '@/components/TaskList.vue'

const props = defineProps({
  project: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['refresh'])

const isStarting = ref(false)
const enableVlmCleaning = ref(true)
const processRange = ref('all')

const startAutoAnnotation = async () => {
  try {
    isStarting.value = true
    
    await ElMessageBox.confirm(
      `即将启动一键自动标注流程：
      - 项目：${props.project.name}
      - 类别数：${props.project.labels?.length || 0}
      - 范围：${processRange.value === 'all' ? '全部图片' : '未处理图片'}
      - VLM 清洗：${enableVlmCleaning.value ? '开启' : '关闭'}
      
      此操作将在后台异步执行，是否继续？`,
      '确认启动',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
        distinguishCancelAndClose: true
      }
    )
    
    const response = await autoAnnotationAPI.startAutoAnnotation(props.project.id)
    
    ElMessage.success({
      message: response.message || '自动标注已启动！',
      duration: 3000
    })
    
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
.auto-annotation {
  padding: 20px;
}

.auto-annotation-panel {
  margin-bottom: 20px;
}

.card-title {
  font-weight: bold;
  font-size: 16px;
}

.info-section {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
}

.info-item {
  display: flex;
  flex-direction: column;
  min-width: 150px;
}

.info-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 5px;
}

.info-value {
  font-size: 16px;
  font-weight: bold;
  color: #303133;
}

.labels-section {
  padding: 15px;
  background-color: #f5f7fa;
  border-radius: 4px;
}

.labels-tip {
  margin: 0 0 15px 0;
  font-size: 14px;
  color: #606266;
}

.labels-list {
  display: flex;
  flex-wrap: wrap;
}

.config-section {
  padding: 15px;
}

.config-item {
  margin-bottom: 20px;
}

.config-hint {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-top: 5px;
  margin-left: 10px;
}

.action-section {
  padding: 20px;
  text-align: center;
}

.action-hint {
  margin: 15px 0 0 0;
  font-size: 12px;
  color: #909399;
}
</style>
