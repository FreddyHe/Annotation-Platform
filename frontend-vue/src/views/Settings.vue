<template>
  <div class="settings-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>设置</span>
        </div>
      </template>
      <div class="settings-content">
        <el-row :gutter="20">
          <el-col :xs="24" :sm="24" :md="12">
            <el-card class="config-card">
              <template #header>
                <div class="section-header">
                  <span>VLM 模型配置</span>
                  <el-button
                    type="primary"
                    plain
                    size="small"
                    :loading="testingVlm"
                    @click="handleTestVlm"
                  >
                    测试连通性
                  </el-button>
                </div>
              </template>
              <el-form :model="form" label-width="110px">
                <el-form-item label="API Key">
                  <el-input v-model="form.vlmApiKey" type="password" show-password />
                </el-form-item>
                <el-form-item label="Base URL">
                  <el-input v-model="form.vlmBaseUrl" />
                </el-form-item>
                <el-form-item label="模型名">
                  <el-input v-model="form.vlmModelName" />
                </el-form-item>
              </el-form>
            </el-card>
          </el-col>

          <el-col :xs="24" :sm="24" :md="12">
            <el-card class="config-card">
              <template #header>
                <div class="section-header">
                  <span>LLM 模型配置</span>
                  <el-button
                    type="primary"
                    plain
                    size="small"
                    :loading="testingLlm"
                    @click="handleTestLlm"
                  >
                    测试连通性
                  </el-button>
                </div>
              </template>
              <el-form :model="form" label-width="110px">
                <el-form-item label="API Key">
                  <el-input v-model="form.llmApiKey" type="password" show-password />
                </el-form-item>
                <el-form-item label="Base URL">
                  <el-input v-model="form.llmBaseUrl" />
                </el-form-item>
                <el-form-item label="模型名">
                  <el-input v-model="form.llmModelName" />
                </el-form-item>
              </el-form>
            </el-card>
          </el-col>
        </el-row>

        <div class="actions">
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
          <el-button :loading="loading" @click="loadConfig">刷新</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userAPI } from '@/api'

const loading = ref(false)
const saving = ref(false)
const testingVlm = ref(false)
const testingLlm = ref(false)

const form = ref({
  vlmApiKey: '',
  vlmBaseUrl: '',
  vlmModelName: '',
  llmApiKey: '',
  llmBaseUrl: '',
  llmModelName: ''
})

const loadConfig = async () => {
  loading.value = true
  try {
    const res = await userAPI.getModelConfig()
    const data = res.data || {}
    form.value = {
      vlmApiKey: data.vlmApiKey || '',
      vlmBaseUrl: data.vlmBaseUrl || '',
      vlmModelName: data.vlmModelName || '',
      llmApiKey: data.llmApiKey || '',
      llmBaseUrl: data.llmBaseUrl || '',
      llmModelName: data.llmModelName || ''
    }
  } finally {
    loading.value = false
  }
}

const handleSave = async () => {
  saving.value = true
  try {
    await userAPI.updateModelConfig(form.value)
    ElMessage.success('保存成功')
    await loadConfig()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const handleTestVlm = async () => {
  testingVlm.value = true
  try {
    const res = await userAPI.testVlmModelConfig()
    if (res.data?.success) {
      ElMessage.success('VLM 连接正常')
    } else {
      ElMessage.error('VLM 连接失败')
    }
  } catch (e) {
    ElMessage.error(e.message || 'VLM 测试失败')
  } finally {
    testingVlm.value = false
  }
}

const handleTestLlm = async () => {
  testingLlm.value = true
  try {
    const res = await userAPI.testLlmModelConfig()
    if (res.data?.success) {
      ElMessage.success('LLM 连接正常')
    } else {
      ElMessage.error('LLM 连接失败')
    }
  } catch (e) {
    ElMessage.error(e.message || 'LLM 测试失败')
  } finally {
    testingLlm.value = false
  }
}

onMounted(() => {
  loadConfig()
})
</script>

<style scoped>
.settings-container {
  padding: 20px;
}

.card-header {
  font-size: 18px;
  font-weight: bold;
}

.settings-content {
  padding: 20px 0;
}

.config-card {
  margin-bottom: 20px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  margin-top: 10px;
}
</style>
