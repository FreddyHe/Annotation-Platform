import request from '@/utils/request'

export const authAPI = {
  login(data) {
    return request({
      url: '/auth/login',
      method: 'post',
      data
    })
  },

  register(data) {
    return request({
      url: '/auth/register',
      method: 'post',
      data
    })
  },

  logout() {
    return request({
      url: '/auth/logout',
      method: 'post'
    })
  },

  refreshToken() {
    return request({
      url: '/auth/refresh',
      method: 'post'
    })
  }
}

export const userAPI = {
  getCurrentUser() {
    return request({
      url: '/users/me',
      method: 'get'
    })
  },
  
  getOrganizationStats() {
    return request({
      url: '/users/me/stats',
      method: 'get'
    })
  },

  getUserProfile() {
    return request({
      url: '/users/me/profile',
      method: 'get'
    })
  },

  getUserById(id) {
    return request({
      url: `/users/${id}`,
      method: 'get'
    })
  },

  getUsers(params) {
    return request({
      url: '/users',
      method: 'get',
      params
    })
  },

  getModelConfig() {
    return request({
      url: '/api/user/model-config',
      method: 'get'
    })
  },

  updateModelConfig(data) {
    return request({
      url: '/api/user/model-config',
      method: 'put',
      data
    })
  },

  testVlmModelConfig() {
    return request({
      url: '/api/user/model-config/test-vlm',
      method: 'post'
    })
  },

  testLlmModelConfig() {
    return request({
      url: '/api/user/model-config/test-llm',
      method: 'post'
    })
  }
}

export const projectAPI = {
  createProject(data) {
    return request({
      url: '/projects',
      method: 'post',
      data
    })
  },

  getProjectById(id) {
    return request({
      url: `/projects/${id}`,
      method: 'get'
    })
  },

  getProjects(params) {
    return request({
      url: '/projects',
      method: 'get',
      params
    })
  },

  updateProject(id, data) {
    return request({
      url: `/projects/${id}`,
      method: 'put',
      data
    })
  },

  deleteProject(id) {
    return request({
      url: `/projects/${id}`,
      method: 'delete'
    })
  },

  getProjectImages(id, params) {
    return request({
      url: `/projects/${id}/images`,
      method: 'get',
      params
    })
  },

  getProjectStats(id) {
    return request({
      url: `/projects/${id}/stats`,
      method: 'get'
    })
  },

  getReviewStats(id) {
    return request({
      url: `/projects/${id}/review-stats`,
      method: 'get'
    })
  },

  getReviewResults(id) {
    return request({
      url: `/projects/${id}/review-results`,
      method: 'get'
    })
  },

  exportResults(data) {
    return request({
      url: `/projects/${data.projectId}/export`,
      method: 'post',
      data
    })
  },

  deleteExport(exportId) {
    return request({
      url: `/projects/exports/${exportId}`,
      method: 'delete'
    })
  }
}

export const uploadAPI = {
  uploadChunk(formData) {
    return request({
      url: '/upload/chunk',
      method: 'post',
      data: formData
    })
  },

  mergeChunks(data) {
    return request({
      url: '/upload/merge',
      method: 'post',
      data
    })
  },

  getUploadProgress(fileId) {
    return request({
      url: `/upload/progress/${fileId}`,
      method: 'get'
    })
  },

  deleteFile(filePath) {
    return request({
      url: '/upload/file',
      method: 'delete',
      params: { filePath }
    })
  },

  extractVideoFrames(data) {
    return request({
      url: '/upload/extract-frames',
      method: 'post',
      data
    })
  }
}

export const algorithmAPI = {
  getTasks(params) {
    return request({
      url: '/algorithm/tasks',
      method: 'get',
      params
    })
  },

  runDinoDetection(data) {
    return request({
      url: '/algorithm/dino/detect',
      method: 'post',
      data
    })
  },

  getDinoTaskStatus(taskId) {
    return request({
      url: `/algorithm/dino/status/${taskId}`,
      method: 'get'
    })
  },

  getDinoTaskResults(taskId) {
    return request({
      url: `/algorithm/dino/results/${taskId}`,
      method: 'get'
    })
  },

  cancelDinoTask(taskId) {
    return request({
      url: `/algorithm/dino/cancel/${taskId}`,
      method: 'post'
    })
  },

  runVlmCleaning(data) {
    return request({
      url: '/algorithm/vlm/clean',
      method: 'post',
      data
    })
  },

  getVlmTaskStatus(taskId) {
    return request({
      url: `/algorithm/vlm/status/${taskId}`,
      method: 'get'
    })
  },

  getVlmTaskResults(taskId) {
    return request({
      url: `/algorithm/vlm/results/${taskId}`,
      method: 'get'
    })
  },

  cancelVlmTask(taskId) {
    return request({
      url: `/algorithm/vlm/cancel/${taskId}`,
      method: 'post'
    })
  },

  runYoloDetection(data) {
    return request({
      url: '/algorithm/yolo/detect',
      method: 'post',
      data
    })
  },

  getYoloTaskStatus(taskId) {
    return request({
      url: `/algorithm/yolo/status/${taskId}`,
      method: 'get'
    })
  },

  getYoloTaskResults(taskId) {
    return request({
      url: `/algorithm/yolo/results/${taskId}`,
      method: 'get'
    })
  },

  cancelYoloTask(taskId) {
    return request({
      url: `/algorithm/yolo/cancel/${taskId}`,
      method: 'post'
    })
  }
}

export const autoAnnotationAPI = {
  startAutoAnnotation(projectId, params = {}) {
    return request({
      url: `/auto-annotation/start/${projectId}`,
      method: 'post',
      data: params
    })
  },

  getTaskStatus(taskId) {
    return request({
      url: `/auto-annotation/status/${taskId}`,
      method: 'get'
    })
  },

  getTaskResults(taskId) {
    return request({
      url: `/auto-annotation/results/${taskId}`,
      method: 'get'
    })
  }
}

export const labelStudioAPI = {
  getLoginUrl(params) {
    return request({
      url: '/label-studio/login-url',
      method: 'get',
      params
    })
  },

  syncUser() {
    return request({
      url: '/label-studio/sync-user',
      method: 'post'
    })
  },

  syncProject(projectId) {
    return request({
      url: `/label-studio/sync-project/${projectId}`,
      method: 'post'
    })
  },

  getUserInfo(lsToken) {
    return request({
      url: '/label-studio/user-info',
      method: 'get',
      params: { lsToken }
    })
  }
}

export const trainingAPI = {
  startTraining(data) {
    return request({
      url: '/training/start',
      method: 'post',
      data
    })
  },

  getTrainingRecord(id) {
    return request({
      url: `/training/record/${id}`,
      method: 'get'
    })
  },

  getTrainingRecordByTaskId(taskId) {
    return request({
      url: `/training/record/task/${taskId}`,
      method: 'get'
    })
  },

  getTrainingRecordsByProject(projectId) {
    return request({
      url: `/training/project/${projectId}`,
      method: 'get'
    })
  },

  getTrainingRecordsByUser() {
    return request({
      url: '/training/user',
      method: 'get'
    })
  },

  getTrainingLog(taskId) {
    return request({
      url: `/training/log/${taskId}`,
      method: 'get'
    })
  },

  cancelTraining(id) {
    return request({
      url: `/training/cancel/${id}`,
      method: 'post'
    })
  },

  getTrainingResults(taskId) {
    return request({
      url: `/training/results/${taskId}`,
      method: 'get'
    })
  },

  getCompletedTrainings() {
    return request({
      url: '/training/completed',
      method: 'get'
    })
  }
}

export const modelTestAPI = {
  startTest(data) {
    const isFormData = data instanceof FormData
    return request({
      url: isFormData ? '/test/start/upload' : '/test/start',
      method: 'post',
      data
    })
  },

  getTestStatus(taskId) {
    return request({
      url: `/test/status/${taskId}`,
      method: 'get'
    })
  },

  getTestResults(taskId) {
    return request({
      url: `/test/results/${taskId}`,
      method: 'get'
    })
  },

  getTestResultForImage(taskId, imageIndex) {
    return request({
      url: `/test/results/${taskId}/image/${imageIndex}`,
      method: 'get'
    })
  },

  cancelTest(taskId) {
    return request({
      url: `/test/cancel/${taskId}`,
      method: 'post'
    })
  }
}
