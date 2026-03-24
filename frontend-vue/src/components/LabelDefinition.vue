<template>
  <div class="label-definition">
    <p class="section-desc">定义检测目标的类别，用于算法标注和 Label Studio 标签配置</p>
    <el-form :model="form" label-width="120px">
      <el-form-item label="类别数量">
        <el-input-number v-model="labelCount" :min="1" :max="20" @change="handleLabelCountChange" />
      </el-form-item>
    </el-form>
    <div class="labels-container">
      <div v-for="(label, index) in labels" :key="index" class="label-item">
        <div class="label-header"><el-tag type="primary" size="default">类别 {{ index + 1 }}</el-tag></div>
        <el-row :gutter="20">
          <el-col :span="12"><el-input v-model="label.name" placeholder="类别名称（如：person, car...）" clearable><template #prepend>名称</template></el-input></el-col>
          <el-col :span="12"><el-input v-model="label.definition" type="textarea" :rows="3" placeholder="类别定义（详细描述此类别的特征）" clearable><template #prepend>定义</template></el-input></el-col>
        </el-row>
      </div>
    </div>
    <div class="actions"><el-button type="primary" size="large" @click="handleSave" :loading="saving"><el-icon><Check /></el-icon>保存类别定义</el-button></div>
  </div>
</template>

<script setup>
import { ref, watch, computed } from 'vue'
import { projectAPI } from '@/api'
import { ElMessage } from 'element-plus'

const props = defineProps({ project: { type: Object, required: true } })
const emit = defineEmits(['refresh'])
const saving = ref(false); const labelCount = ref(1); const labels = ref([])

const initLabels = () => {
  const existingLabels = props.project.labels || []; const existingDefinitions = props.project.labelDefinitions || {}
  labelCount.value = Math.max(existingLabels.length, 1); labels.value = []
  for (let i = 0; i < labelCount.value; i++) { const name = existingLabels[i] || `class_${i + 1}`; labels.value.push({ name, definition: existingDefinitions[name] || '' }) }
}
const handleLabelCountChange = (value) => {
  const currentCount = labels.value.length
  if (value > currentCount) { for (let i = currentCount; i < value; i++) labels.value.push({ name: `class_${i + 1}`, definition: '' }) }
  else if (value < currentCount) { labels.value = labels.value.slice(0, value) }
}
const handleSave = async () => {
  if (!props.project || !props.project.id || props.project.id === 'null') return
  try {
    saving.value = true; const labelsMap = {}; labels.value.forEach(label => { if (label.name) labelsMap[label.name] = label.definition })
    if (Object.keys(labelsMap).length === 0) { ElMessage.warning('请至少定义一个类别'); return }
    await projectAPI.updateProject(props.project.id, { labels: Object.keys(labelsMap), labelDefinitions: labelsMap })
    ElMessage.success('类别定义保存成功'); emit('refresh')
  } catch (error) { ElMessage.error('保存失败：' + (error.message || '未知错误')) } finally { saving.value = false }
}
watch(() => props.project, () => { initLabels() }, { immediate: true })
</script>

<style scoped>
.label-definition { }
.section-desc { color: var(--gray-500); font-size: 13px; margin-bottom: 20px; }
.labels-container { margin: 20px 0; }
.label-item { background: var(--gray-50); padding: 16px 20px; border-radius: var(--radius-md); margin-bottom: 16px; border-left: 3px solid var(--brand-400); }
.label-header { margin-bottom: 12px; }
.actions { text-align: center; margin-top: 24px; }
.actions .el-button { min-width: 200px; }
</style>
