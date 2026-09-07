<template>
  <div class="model-registry-page">
    <el-page-header @back="goBack" content="模型注册表">
      <template #extra>
        <el-button type="primary" :loading="loading.sync" @click="syncModels">
          <el-icon><Refresh /></el-icon>
          同步模型
        </el-button>
      </template>
    </el-page-header>

    <div class="registry-shell">
      <div class="toolbar">
        <el-segmented v-model="modelType" :options="typeOptions" />
        <el-select v-model="status" placeholder="状态" clearable style="width: 160px" @change="loadModels">
          <el-option label="全部状态" value="" />
          <el-option label="可用" value="AVAILABLE" />
          <el-option label="不可用" value="UNAVAILABLE" />
        </el-select>
      </div>

      <div class="metric-row">
        <div class="metric">
          <span>模型数量</span>
          <strong>{{ total }}</strong>
        </div>
        <div class="metric">
          <span>xingmu 可验收场景</span>
          <strong>{{ xingmuCount }}</strong>
        </div>
        <div class="metric">
          <span>隐藏场景</span>
          <strong>{{ hiddenCount }}</strong>
        </div>
        <div class="metric">
          <span>大模型策略</span>
          <strong>本地部署</strong>
        </div>
      </div>

      <el-alert
        type="info"
        :closable="false"
        title="仅使用前端展示的 32 个 xingmu 可验收场景，隐藏场景不会作为默认可路由模型。" />

      <el-table v-loading="loading.list" :data="models" border class="model-table">
        <el-table-column prop="modelId" label="模型标识" min-width="220" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column label="类型" width="150">
          <template #default="{ row }">{{ modelTypeText(row.modelType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类别" min-width="220">
          <template #default="{ row }">
            <div class="tag-list">
              <el-tag v-for="label in (row.classes || []).slice(0, 4)" :key="label" size="small">
                {{ label }}
              </el-tag>
              <span v-if="(row.classes || []).length > 4">等 {{ row.classes.length }} 类</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="240">
          <template #default="{ row }">
            <span v-if="row.status === 'UNAVAILABLE'">{{ row.unavailableReason || unavailableText(row) }}</span>
            <span v-else>{{ row.unavailableReason || row.licenseInfo || '已纳入质量优先路由候选' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { autoLabelToModelAPI } from '@/api'

const router = useRouter()
const models = ref([])
const total = ref(0)
const modelType = ref('全部')
const status = ref('')
const loading = ref({ list: false, sync: false }).value

const typeOptions = ['全部', 'xingmu 场景', '本地/系统模型']

const xingmuCount = computed(() => models.value.filter(item => item.modelType === 'XINGMU_SCENARIO').length)
const hiddenCount = computed(() => 8)

const loadModels = async () => {
  loading.list = true
  try {
    const params = {}
    if (status.value) params.status = status.value
    if (modelType.value === 'xingmu 场景') params.modelType = 'XINGMU_SCENARIO'
    const response = await autoLabelToModelAPI.listModels(params)
    const items = response.data?.items || []
    models.value = modelType.value === '本地/系统模型'
      ? items.filter(item => item.modelType !== 'XINGMU_SCENARIO')
      : items
    total.value = models.value.length
  } finally {
    loading.list = false
  }
}

const syncModels = async () => {
  loading.sync = true
  try {
    await autoLabelToModelAPI.syncModels()
    await loadModels()
    ElMessage.success('模型注册表已同步')
  } finally {
    loading.sync = false
  }
}

const goBack = () => {
  router.push('/projects')
}

const modelTypeText = value => {
  const map = {
    XINGMU_SCENARIO: 'xingmu 场景',
    GROUNDING_DINO: '开放词汇检测',
    LOCAL_GROUNDING_LMM: '本地定位模型',
    LOCAL_VLM: '本地视觉语言模型',
    YOLO_WORLD_PLACEHOLDER: '预留模型'
  }
  return map[value] || '系统模型'
}

const statusText = value => {
  const map = { AVAILABLE: '可用', UNAVAILABLE: '不可用', DISABLED: '停用' }
  return map[value] || '待确认'
}

const statusType = value => {
  const map = { AVAILABLE: 'success', UNAVAILABLE: 'warning', DISABLED: 'info' }
  return map[value] || 'info'
}

const unavailableText = row => {
  if (row.modelType === 'YOLO_WORLD_PLACEHOLDER') {
    return 'YOLO-World 仅作为后续实时开放词汇模型预留，当前不参与主线自动标注。'
  }
  if (row.modelType === 'LOCAL_GROUNDING_LMM' || row.modelType === 'LOCAL_VLM') {
    return '本地质量增强模型服务当前不可用；系统会继续使用可用主模型和人工复核流程。'
  }
  return '该模型当前不可用；系统会自动降级到可用模型。'
}

watch(modelType, loadModels)
onMounted(loadModels)
</script>

<style scoped>
.registry-shell {
  margin-top: 18px;
  background: #fff;
  border: 0.5px solid var(--gray-200);
  border-radius: 8px;
  padding: 18px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.metric-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(120px, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.metric {
  border: 0.5px solid var(--gray-200);
  border-radius: 8px;
  padding: 12px;
}

.metric span {
  display: block;
  color: var(--gray-500);
  font-size: 12px;
}

.metric strong {
  display: block;
  margin-top: 6px;
  color: var(--gray-900);
  font-size: 18px;
}

.model-table {
  margin-top: 14px;
}

.tag-list {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

@media (max-width: 900px) {
  .toolbar,
  .metric-row {
    grid-template-columns: 1fr;
  }

  .toolbar {
    flex-direction: column;
  }
}
</style>
