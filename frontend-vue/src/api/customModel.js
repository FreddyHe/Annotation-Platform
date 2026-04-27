import request from '@/utils/request'

// 创建训练任务
export function createTrainingTask(data) {
  return request({ url: '/custom-models/train', method: 'post', data })
}

// 训练前数据集预检
export function inspectTrainingDataset(data) {
  return request({ url: '/custom-models/inspect-dataset', method: 'post', data })
}

// 获取当前用户所有训练任务
export function listTrainingTasks() {
  return request({ url: '/custom-models', method: 'get' })
}

// 获取训练状态（触发后端同步算法服务最新状态）
export function getModelStatus(id) {
  return request({ url: `/custom-models/${id}/status`, method: 'get' })
}

// 获取训练日志
export function getTrainingLogs(id) {
  return request({ url: `/custom-models/${id}/logs`, method: 'get' })
}

// 重新提交训练任务
export function retryTrainingTask(id) {
  return request({ url: `/custom-models/${id}/retry`, method: 'post' })
}

// 删除训练任务/模型
export function deleteCustomModel(id) {
  return request({ url: `/custom-models/${id}`, method: 'delete' })
}

// 获取所有已完成的自定义模型（供检测页面使用）
export function getAvailableModels() {
  return request({ url: '/custom-models/available', method: 'get' })
}

// 获取内置检测模型信息
export function getBuiltinModelInfo() {
  return request({ url: '/detection/model-info', method: 'get' })
}

// 执行单类别检测
export function detectSingleClass(data) {
  return request({
    url: '/detection/single-class',
    method: 'post',
    data
  })
}

// 上传训练数据集ZIP包
export function uploadTrainingDataset(data) {
  return request({
    url: '/upload/training-dataset',
    method: 'post',
    data
  })
}

// 获取后端持久化检测历史
export function listDetectionHistory() {
  return request({ url: '/detection/single-class/history', method: 'get' })
}

// 清空后端持久化检测历史
export function clearDetectionHistoryRecords() {
  return request({ url: '/detection/single-class/history', method: 'delete' })
}
