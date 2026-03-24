import request from '@/utils/request'

// 创建训练任务
export function createTrainingTask(data) {
  return request({ url: '/custom-models/train', method: 'post', data })
}

// 获取当前用户所有训练任务
export function listTrainingTasks() {
  return request({ url: '/custom-models', method: 'get' })
}

// 获取训练状态（触发后端同步算法服务最新状态）
export function getModelStatus(id) {
  return request({ url: `/custom-models/${id}/status`, method: 'get' })
}

// 获取所有已完成的自定义模型（供检测页面使用）
export function getAvailableModels() {
  return request({ url: '/custom-models/available', method: 'get' })
}
