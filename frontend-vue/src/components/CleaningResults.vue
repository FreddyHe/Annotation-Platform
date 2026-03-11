<template>
  <div class="cleaning-results">
    <div class="toolbar">
      <el-button @click="loadResults" :loading="loading">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
      <el-select
        v-model="filterAction"
        placeholder="筛选操作"
        clearable
        style="width: 150px; margin-left: 10px;"
        @change="loadResults"
      >
        <el-option label="保留" value="keep" />
        <el-option label="删除" value="remove" />
      </el-select>
    </div>

    <el-table :data="filteredResults" v-loading="loading" style="width: 100%">
      <el-table-column prop="imageName" label="图片名称" width="200" />
      <el-table-column prop="originalLabels" label="原始标签" width="200">
        <template #default="{ row }">
          <el-tag
            v-for="label in row.originalLabels"
            :key="label"
            type="info"
            size="small"
            style="margin-right: 5px;"
          >
            {{ label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="cleanedLabels" label="清洗后标签" width="200">
        <template #default="{ row }">
          <el-tag
            v-for="label in row.cleanedLabels"
            :key="label"
            type="success"
            size="small"
            style="margin-right: 5px;"
          >
            {{ label }}
          </el-tag>
          <span v-if="row.cleanedLabels.length === 0" style="color: #909399;">
            无
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="removedLabels" label="删除的标签" width="200">
        <template #default="{ row }">
          <el-tag
            v-for="label in row.removedLabels"
            :key="label"
            type="danger"
            size="small"
            style="margin-right: 5px;"
          >
            {{ label }}
          </el-tag>
          <span v-if="row.removedLabels.length === 0" style="color: #909399;">
            无
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="action" label="操作" width="100">
        <template #default="{ row }">
          <el-tag :type="row.action === 'keep' ? 'success' : 'danger'">
            {{ row.action === 'keep' ? '保留' : '删除' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="200" />
      <el-table-column label="预览" width="100">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="previewImage(row)">
            预览
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="results.length === 0" class="empty-state">
      <el-empty description="暂无清洗结果" />
    </div>

    <el-pagination
      v-if="totalPages > 1"
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[20, 50, 100]"
      :total="filteredResults.length"
      layout="total, sizes, prev, pager, next, jumper"
      style="margin-top: 20px; text-align: right;"
    />

    <el-dialog v-model="previewVisible" title="图片预览" width="80%">
      <div v-if="currentResult" class="preview-container">
        <el-image
          :src="currentResult.imageUrl"
          fit="contain"
          style="width: 100%; max-height: 600px;"
        />
        <div class="preview-info">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="图片名称">
              {{ currentResult.imageName }}
            </el-descriptions-item>
            <el-descriptions-item label="操作">
              <el-tag :type="currentResult.action === 'keep' ? 'success' : 'danger'">
                {{ currentResult.action === 'keep' ? '保留' : '删除' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="原始标签" :span="2">
              <el-tag
                v-for="label in currentResult.originalLabels"
                :key="label"
                type="info"
                size="small"
                style="margin-right: 5px;"
              >
                {{ label }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="清洗后标签" :span="2">
              <el-tag
                v-for="label in currentResult.cleanedLabels"
                :key="label"
                type="success"
                size="small"
                style="margin-right: 5px;"
              >
                {{ label }}
              </el-tag>
              <span v-if="currentResult.cleanedLabels.length === 0" style="color: #909399;">
                无
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="原因" :span="2">
              {{ currentResult.reason }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { projectAPI } from '@/api'
import { ElMessage } from 'element-plus'

const props = defineProps({
  project: {
    type: Object,
    required: true
  }
})

const loading = ref(false)
const results = ref([])
const filterAction = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const previewVisible = ref(false)
const currentResult = ref(null)

const filteredResults = computed(() => {
  let filtered = results.value

  if (filterAction.value) {
    filtered = filtered.filter(r => r.action === filterAction.value)
  }

  return filtered
})

const totalPages = computed(() => Math.ceil(filteredResults.value.length / pageSize.value))

const loadResults = async () => {
  if (!props.project || !props.project.id || props.project.id === 'null') {
    return
  }
  
  try {
    loading.value = true
    const response = await projectAPI.getProjectImages(props.project.id, {
      page: 1,
      pageSize: 1000
    })
    
    const images = response.data.images || []
    results.value = []
    
    images.forEach(img => {
      if (img.cleaningResults && img.cleaningResults.length > 0) {
        img.cleaningResults.forEach(clean => {
          results.value.push({
            imageName: img.name,
            imagePath: img.path,
            imageUrl: img.url,
            originalLabels: clean.originalLabels || [],
            cleanedLabels: clean.cleanedLabels || [],
            removedLabels: clean.removedLabels || [],
            action: clean.cleanedLabels && clean.cleanedLabels.length > 0 ? 'keep' : 'remove',
            reason: clean.reason || ''
          })
        })
      }
    })
  } catch (error) {
    ElMessage.error('加载清洗结果失败')
  } finally {
    loading.value = false
  }
}

const previewImage = (result) => {
  currentResult.value = result
  previewVisible.value = true
}

onMounted(() => {
  loadResults()
})
</script>

<style scoped>
.cleaning-results {
  padding: 20px;
}

.toolbar {
  margin-bottom: 20px;
  display: flex;
  align-items: center;
}

.empty-state {
  padding: 60px 0;
}

.preview-container {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.preview-info {
  margin-top: 20px;
}
</style>
