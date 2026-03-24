<template>
  <div class="model-training-page">
    <div class="page-header">
      <h2>单类别模型训练</h2>
      <p style="color: #909399; margin: 4px 0 0;">
        从 Roboflow 下载数据集，自动训练 YOLO 检测模型，训练完成后可在"单类别检测"页面使用
      </p>
    </div>

    <el-row :gutter="24">
      <!-- ========== 左栏：新建训练任务 ========== -->
      <el-col :span="10">
        <el-card shadow="hover">
          <template #header>
            <span style="font-weight: 600;">新建训练任务</span>
          </template>

          <el-form :model="form" :rules="rules" ref="formRef" label-position="top">

            <el-form-item label="模型名称" prop="modelName">
              <el-input v-model="form.modelName" placeholder="例如：罂粟花检测模型" clearable />
            </el-form-item>

            <el-form-item label="Roboflow 下载命令" prop="downloadCommand">
              <el-input
                v-model="form.downloadCommand"
                type="textarea"
                :rows="8"
                placeholder="支持以下三种格式（从 Roboflow Universe 项目页复制）：

1. 直接 URL：
https://universe.roboflow.com/ds/xxx?key=yyy

2. curl 命令：
curl -L &quot;https://universe.roboflow.com/ds/xxx?key=yyy&quot; > roboflow.zip

3. Python SDK 代码：
from roboflow import Roboflow
rf = Roboflow(api_key=&quot;xxx&quot;)
project = rf.workspace(&quot;...&quot;).project(&quot;...&quot;)
version = project.version(2)
dataset = version.download(&quot;coco&quot;)"
              />
              <div style="margin-top: 6px; font-size: 12px; color: #909399; line-height: 1.6;">
                在 Roboflow Universe 项目页面点击 "Download Dataset"，选择格式后复制下载代码粘贴到此处。
                推荐选择 YOLO 或 COCO 格式。
              </div>
            </el-form-item>

            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="训练轮数 (Epochs)">
                  <el-input-number v-model="form.epochs" :min="1" :max="500" :step="5" style="width: 100%;" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="Batch Size">
                  <el-select v-model="form.batchSize" style="width: 100%;">
                    <el-option :value="4"  label="4（低显存）" />
                    <el-option :value="8"  label="8" />
                    <el-option :value="16" label="16（推荐）" />
                    <el-option :value="32" label="32（大显存）" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>

            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="handleSubmit" style="width: 100%;" size="large">
                开始训练
              </el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <!-- ========== 右栏：训练任务列表 ========== -->
      <el-col :span="14">
        <el-card shadow="hover">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: 600;">训练任务列表</span>
              <el-button text @click="refreshTasks">刷新</el-button>
            </div>
          </template>

          <el-table :data="tasks" stripe v-loading="loadingTasks" empty-text="暂无训练任务"
                    highlight-current-row @current-change="handleRowClick">
            <el-table-column prop="modelName" label="模型名称" min-width="130" />
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" size="small" effect="plain">
                  {{ statusText(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="epochs" label="Epochs" width="75" align="center" />
            <el-table-column label="mAP50" width="80" align="center">
              <template #default="{ row }">
                <span v-if="row.mapScore != null">{{ (row.mapScore * 100).toFixed(1) }}%</span>
                <span v-else style="color:#c0c4cc;">-</span>
              </template>
            </el-table-column>
            <el-table-column label="类别数" width="72" align="center">
              <template #default="{ row }">
                {{ row.classes && row.classes.length ? row.classes.length : '-' }}
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="155">
              <template #default="{ row }">
                {{ formatTime(row.createdAt) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="center">
              <template #default="{ row }">
                <el-button v-if="row.status === 'COMPLETED'" type="primary" link size="small"
                           @click.stop="goToDetection(row)">
                  去检测
                </el-button>
                <span v-else-if="isInProgress(row.status)" style="color: #909399; font-size: 12px;">
                  进行中...
                </span>
                <el-tag v-else-if="row.status === 'FAILED'" type="danger" size="small" effect="plain">
                  失败
                </el-tag>
              </template>
            </el-table-column>
          </el-table>

          <!-- 选中行的详情展示 -->
          <div v-if="selectedTask" style="margin-top: 16px; padding: 12px; background: #f5f7fa; border-radius: 8px;">
            <p style="margin: 0 0 8px; font-weight: 600;">{{ selectedTask.modelName }}</p>
            <p style="margin: 0 0 8px; font-size: 13px; color: #606266;">
              状态信息：{{ selectedTask.statusMessage || '-' }}
            </p>
            <div v-if="selectedTask.classes && selectedTask.classes.length">
              <span style="font-size: 13px; color: #606266;">可检测类别：</span>
              <el-tag v-for="cls in selectedTask.classes" :key="cls.classId"
                      size="small" style="margin: 2px 4px 2px 0;" effect="plain">
                {{ cls.cnName || cls.className }}
              </el-tag>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createTrainingTask, listTrainingTasks, getModelStatus } from '@/api/customModel'

const router = useRouter()
const formRef = ref(null)
const submitting = ref(false)
const loadingTasks = ref(false)
const tasks = ref([])
const selectedTask = ref(null)
let pollTimer = null

const form = reactive({
  modelName: '',
  downloadCommand: '',
  epochs: 10,
  batchSize: 16
})

const rules = {
  modelName: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  downloadCommand: [{ required: true, message: '请输入 Roboflow 下载命令', trigger: 'blur' }]
}

function isInProgress(status) {
  return ['PENDING', 'DOWNLOADING', 'CONVERTING', 'TRAINING'].includes(status)
}

function statusTagType(status) {
  return { PENDING: 'info', DOWNLOADING: 'warning', CONVERTING: 'warning',
           TRAINING: '', COMPLETED: 'success', FAILED: 'danger' }[status] || 'info'
}

function statusText(status) {
  return { PENDING: '等待中', DOWNLOADING: '下载数据中', CONVERTING: '格式转换中',
           TRAINING: '训练中', COMPLETED: '已完成', FAILED: '失败' }[status] || status
}

function formatTime(t) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

function handleRowClick(row) {
  selectedTask.value = row
}

function goToDetection(model) {
  router.push({ path: '/single-class-detection', query: { modelId: model.id } })
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const res = await createTrainingTask({
      modelName: form.modelName,
      downloadCommand: form.downloadCommand,
      epochs: form.epochs,
      batchSize: form.batchSize
    })
    if (res.data?.success !== false) {
      ElMessage.success('训练任务已创建，请在右侧列表查看进度')
      form.modelName = ''
      form.downloadCommand = ''
      form.epochs = 10
      await refreshTasks()
      startPolling()
    }
  } catch (err) {
    ElMessage.error('创建失败：' + (err.response?.data?.message || err.message))
  } finally {
    submitting.value = false
  }
}

async function refreshTasks() {
  loadingTasks.value = true
  try {
    const res = await listTrainingTasks()
    tasks.value = res.data?.data || res.data || []
  } catch (err) {
    console.error('获取任务列表失败:', err)
  } finally {
    loadingTasks.value = false
  }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    const inProgress = tasks.value.filter(t => isInProgress(t.status))
    if (inProgress.length === 0) {
      clearInterval(pollTimer)
      pollTimer = null
      return
    }
    for (const task of inProgress) {
      try { await getModelStatus(task.id) } catch (e) { /* ignore */ }
    }
    await refreshTasks()
  }, 5000)
}

onMounted(async () => {
  await refreshTasks()
  if (tasks.value.some(t => isInProgress(t.status))) {
    startPolling()
  }
})

onUnmounted(() => {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
})
</script>

<style scoped>
.model-training-page { padding: 20px; }
.page-header { margin-bottom: 20px; }
.page-header h2 { margin: 0; font-size: 20px; }
</style>
