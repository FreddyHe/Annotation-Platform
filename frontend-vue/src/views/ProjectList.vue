<template>
  <div class="project-list">
    <PageHeading
      eyebrow="DATA WORKSPACE"
      title="项目管理"
      description="管理数据上传、智能标注、人工复核、训练测试和边端下发的完整项目闭环。"
    >
      <PlatformButton @click="handleCreate">＋ 创建项目</PlatformButton>
    </PageHeading>
    <PanelCard title="项目列表" :description="`当前共 ${total} 个项目`" :padded="false">
      
      <el-table :data="projects" v-loading="loading" style="width: 100%">
        <el-table-column prop="name" label="项目名称" />
        <el-table-column prop="status" label="状态">
          <template #default="{ row }">
            <StatusPill :tone="getStatusTone(row.status)">
              {{ getStatusText(row.status) }}
            </StatusPill>
          </template>
        </el-table-column>
        <el-table-column prop="totalImages" label="图片数" />
        <el-table-column prop="processedImages" label="已处理" />
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="viewProject(row.id)">
              查看
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
    </PanelCard>

    <el-dialog
      v-model="showCreateDialog"
      title="创建新项目"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-form :model="projectForm" :rules="projectRules" ref="projectFormRef" label-width="100px" @submit.prevent>
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
import { getProjectStatusText, getProjectStatusType } from '@/utils/projectStatus'
import PageHeading from '@/components/platform-ui/PageHeading.vue'
import PanelCard from '@/components/platform-ui/PanelCard.vue'
import PlatformButton from '@/components/platform-ui/PlatformButton.vue'
import StatusPill from '@/components/platform-ui/StatusPill.vue'

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
    { min: 3, max: 100, message: '长度在 3 到 100 个字符', trigger: 'blur' }
  ],
  labels: [
    { required: true, message: '至少需要一个标签', trigger: 'change' }
  ]
}

const labelInputVisible = ref(false)
const labelInputValue = ref('')

const getStatusType = (status) => {
  return getProjectStatusType(status)
}

const getStatusTone = (status) => ({
  success: 'success', danger: 'danger', warning: 'warning', primary: 'active', info: 'neutral'
}[getStatusType(status)] || 'neutral')

const getStatusText = (status) => {
  return getProjectStatusText(status)
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
  const nextLabel = labelInputValue.value.trim()
  if (nextLabel) {
    if (projectForm.labels.includes(nextLabel)) {
      ElMessage.warning('标签已存在')
      labelInputValue.value = ''
      labelInputVisible.value = false
      return
    }
    projectForm.labels.push(nextLabel)
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
    
    const labels = [...new Set(projectForm.labels.map(label => label.trim()).filter(Boolean))]
    if (labels.length === 0) {
      ElMessage.warning('至少需要一个标签')
      return
    }
    creating.value = true

    const response = await projectAPI.createProject({
      name: projectForm.name.trim(),
      labels
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
      const data = response.data || {}
      if (Array.isArray(data)) {
        projects.value = data
        total.value = data.length
      } else {
        projects.value = data.content || []
        total.value = data.pageable?.totalElements ?? projects.value.length
      }
    }
  } catch (error) {
    console.error('加载项目列表失败:', error)
    ElMessage.error('加载项目列表失败')
  } finally {
    loading.value = false
  }
}

const formatDate = (dateString) => {
  if (!dateString) return '-'
  const date = new Date(dateString)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

onMounted(() => {
  loadProjects()
})
</script>

<style scoped>
.project-list {
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
