<template>
  <div class="project-list">
    <el-card>
      <template #header>
        <div class="card-header">
          <span class="card-title">项目管理</span>
          <el-button type="primary" @click="handleCreate">
            <el-icon><Plus /></el-icon>
            创建项目
          </el-button>
        </div>
      </template>
      
      <el-table :data="projects" v-loading="loading" style="width: 100%">
        <el-table-column prop="name" label="项目名称" />
        <el-table-column prop="status" label="状态">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalImages" label="图片数" />
        <el-table-column prop="processedImages" label="已处理" />
        <el-table-column prop="createdAt" label="创建时间" />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="viewProject(row.id)">
              查看
            </el-button>
            <el-button type="primary" link size="small" @click="editProject(row.id)">
              编辑
            </el-button>
            <el-button type="danger" link size="small" @click="deleteProject(row.id)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
        style="margin-top: 20px; justify-content: flex-end; display: flex;"
      />
    </el-card>

    <el-dialog
      v-model="showCreateDialog"
      title="创建新项目"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-form :model="projectForm" :rules="projectRules" ref="projectFormRef" label-width="100px">
        <el-form-item label="项目名称" prop="name">
          <el-input v-model="projectForm.name" placeholder="请输入项目名称" />
        </el-form-item>
        
        <el-form-item label="标签" prop="labels">
          <div class="tag-group">
            <el-tag
              v-for="(tag, index) in projectForm.labels"
              :key="index"
              closable
              @close="removeLabel(index)"
              size="default"
            >
              {{ tag }}
            </el-tag>
            <el-input
              v-if="labelInputVisible"
              ref="labelInputRef"
              v-model="labelInputValue"
              class="tag-input"
              size="small"
              @keyup.enter="addLabel"
              @blur="addLabel"
            />
            <el-button
              v-else
              size="small"
              @click="showLabelInput"
            >
              + 添加标签
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="submitCreate" :loading="creating">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectAPI } from '@/api/index'

const router = useRouter()

const loading = ref(false)
const showCreateDialog = ref(false)
const creating = ref(false)
const projectFormRef = ref(null)
const labelInputRef = ref(null)

const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const projects = ref([])

const projectForm = reactive({
  name: '',
  labels: ['object']
})

const projectRules = {
  name: [
    { required: true, message: '请输入项目名称', trigger: 'blur' },
    { min: 1, max: 100, message: '长度在 1 到 100 个字符', trigger: 'blur' }
  ],
  labels: [
    { required: true, message: '至少需要一个标签', trigger: 'change' }
  ]
}

const labelInputVisible = ref(false)
const labelInputValue = ref('')

const getStatusType = (status) => {
  const typeMap = {
    'DRAFT': 'info',
    'UPLOADING': 'warning',
    'PROCESSING': 'primary',
    'COMPLETED': 'success',
    'FAILED': 'danger'
  }
  return typeMap[status] || 'info'
}

const getStatusText = (status) => {
  const textMap = {
    'DRAFT': '草稿',
    'UPLOADING': '上传中',
    'PROCESSING': '处理中',
    'COMPLETED': '已完成',
    'FAILED': '失败'
  }
  return textMap[status] || status
}

const handleCreate = () => {
  showCreateDialog.value = true
  nextTick(() => {
    projectForm.name = ''
    projectForm.labels = ['object']
  })
}

const showLabelInput = () => {
  labelInputVisible.value = true
  nextTick(() => {
    labelInputRef.value?.focus()
  })
}

const addLabel = () => {
  if (labelInputValue.value) {
    if (projectForm.labels.includes(labelInputValue.value)) {
      ElMessage.warning('标签已存在')
      return
    }
    projectForm.labels.push(labelInputValue.value)
    labelInputValue.value = ''
  }
  labelInputVisible.value = false
}

const removeLabel = (index) => {
  projectForm.labels.splice(index, 1)
}

const submitCreate = async () => {
  if (!projectFormRef.value) return
  
  try {
    await projectFormRef.value.validate()
    
    creating.value = true
    
    const response = await projectAPI.createProject({
      name: projectForm.name,
      labels: projectForm.labels
    })
    
    creating.value = false
    
    if (response.success) {
      ElMessage.success('创建项目成功')
      showCreateDialog.value = false
      loadProjects()
    } else {
      ElMessage.error(response.message || '创建项目失败')
    }
  } catch (error) {
    creating.value = false
    console.error('创建项目失败:', error)
  }
}

const viewProject = (id) => {
  router.push(`/projects/${id}`)
}

const editProject = (id) => {
  ElMessage.info('编辑项目功能开发中')
}

const deleteProject = async (id) => {
  try {
    await ElMessageBox.confirm('确定要删除该项目吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    
    const response = await projectAPI.deleteProject(id)
    
    if (response.success) {
      ElMessage.success('删除成功')
      loadProjects()
    }
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除失败:', error)
    }
  }
}

const handleSizeChange = (val) => {
  pageSize.value = val
  loadProjects()
}

const handlePageChange = (val) => {
  currentPage.value = val
  loadProjects()
}

const loadProjects = async () => {
  loading.value = true
  try {
    const response = await projectAPI.getProjects({
      page: currentPage.value - 1,
      size: pageSize.value
    })
    
    if (response.success) {
      projects.value = response.data || []
      total.value = projects.value.length
    }
  } catch (error) {
    console.error('加载项目列表失败:', error)
    ElMessage.error('加载项目列表失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadProjects()
})
</script>

<style scoped>
.project-list {
  max-width: 1200px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--gray-900);
}

.tag-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.tag-input {
  width: 120px;
}
</style>
