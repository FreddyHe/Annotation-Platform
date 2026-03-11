<template>
  <div class="model-training">
    <el-page-header @back="goBack" content="模型训练与测试控制台">
      <template #extra>
        <el-tag type="info" size="large">
          <el-icon><Cpu /></el-icon>
          MLOps Platform
        </el-tag>
      </template>
    </el-page-header>

    <el-tabs v-model="activeTab" class="training-tabs" style="margin-top: 20px;">
      <el-tab-pane label="🚀 训练控制台" name="training">
        <TrainingDashboard />
      </el-tab-pane>

      <el-tab-pane label="🏆 模型大厅与测试" name="testing">
        <ModelHub />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Cpu } from '@element-plus/icons-vue'
import TrainingDashboard from '@/components/TrainingDashboard.vue'
import ModelHub from '@/components/ModelHub.vue'

const router = useRouter()
const activeTab = ref('training')

const goBack = () => {
  router.back()
}
</script>

<style scoped>
.model-training {
  padding: 20px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  min-height: 100vh;
}

.el-page-header {
  background: white;
  padding: 20px;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
}

.training-tabs {
  background: white;
  padding: 20px;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
}

:deep(.el-tabs__header) {
  margin: 0 0 20px 0;
}

:deep(.el-tabs__item) {
  font-size: 16px;
  font-weight: 600;
}

:deep(.el-tabs__item.is-active) {
  color: #667eea;
}
</style>
