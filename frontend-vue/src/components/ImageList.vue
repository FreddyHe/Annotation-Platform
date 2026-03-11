<template>
  <div class="image-list">
    <div class="toolbar">
      <el-button @click="loadImages" :loading="loading">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
      <el-input-number
        v-model="pageSize"
        :min="10"
        :max="100"
        :step="10"
        @change="handlePageSizeChange"
        style="width: 120px; margin-left: 10px;"
      />
      <span style="margin-left: 10px;">条/页</span>
    </div>

    <div class="image-grid">
      <div
        v-for="image in images"
        :key="image.id"
        class="image-card"
      >
        <el-image
          :src="image.url"
          :preview-src-list="[image.url]"
          fit="cover"
          class="image-preview"
        >
          <template #error>
            <div class="image-error">
              <el-icon><Picture /></el-icon>
              <span>加载失败</span>
            </div>
          </template>
        </el-image>
        <div class="image-info">
          <div class="image-name">{{ image.name }}</div>
        </div>
      </div>
    </div>

    <div v-if="images.length === 0" class="empty-state">
      <el-empty description="暂无图片数据" />
    </div>

    <div v-if="totalPages > 1" class="pagination">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handlePageSizeChange"
        @current-change="handlePageChange"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { projectAPI } from '@/api'
import { ElMessage } from 'element-plus'

const props = defineProps({
  projectId: {
    type: [String, Number],
    required: true
  }
})

const loading = ref(false)
const images = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)

const totalPages = computed(() => Math.ceil(total.value / pageSize.value))

const loadImages = async () => {
  if (!props.projectId || props.projectId === 'null') {
    return
  }
  
  try {
    loading.value = true
    const response = await projectAPI.getProjectImages(props.projectId, {
      page: currentPage.value,
      size: pageSize.value
    })
    console.log('【Debug】真实的图片接口返回:', response)
    const rawData = response.data?.images || []
    images.value = rawData.map(img => ({
      ...img,
      url: `/api/v1/files/${img.filePath}`,
      name: img.fileName,
      processed: img.status === 'COMPLETED' || img.status === 'PROCESSING'
    }))
    total.value = response.data?.total || 0
  } catch (error) {
    ElMessage.error('加载图片列表失败')
  } finally {
    loading.value = false
  }
}

const handlePageSizeChange = (size) => {
  pageSize.value = size
  currentPage.value = 1
  loadImages()
}

const handlePageChange = (page) => {
  currentPage.value = page
  loadImages()
}

onMounted(() => {
  loadImages()
})
</script>

<style scoped>
.image-list {
  padding: 20px;
}

.toolbar {
  margin-bottom: 20px;
  display: flex;
  align-items: center;
}

.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 20px;
  margin-bottom: 20px;
}

.image-card {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
  transition: all 0.3s;
}

.image-card:hover {
  box-shadow: 0 4px 20px 0 rgba(0, 0, 0, 0.15);
  transform: translateY(-2px);
}

.image-preview {
  width: 100%;
  height: 150px;
  background: #f5f7fa;
}

.image-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #909399;
}

.image-error .el-icon {
  font-size: 32px;
  margin-bottom: 8px;
}

.image-info {
  padding: 12px;
}

.image-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.image-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.empty-state {
  padding: 60px 0;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
</style>
