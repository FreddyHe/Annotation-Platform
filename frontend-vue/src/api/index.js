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

  getProjectVideos(id) {
    return request({
      url: `/projects/${id}/videos`,
      method: 'get'
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
  },

  startTraining(projectId, config) {
    return request({
      url: `/projects/${projectId}/training/start`,
      method: 'post',
      data: config
    })
  },

  getTrainingStatus(projectId) {
    return request({
      url: `/projects/${projectId}/training/status`,
      method: 'get'
    })
  },

  detectWithTrainedModel(projectId, formData) {
    return request({
      url: `/projects/${projectId}/training/detect`,
      method: 'post',
      data: formData,
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
  },

  getLsHealth(projectId) {
    return request({
      url: `/projects/${projectId}/ls-health`,
      method: 'get'
    })
  },

  repairLsBinding(projectId) {
    return request({
      url: `/projects/${projectId}/repair-ls-binding`,
      method: 'post'
    })
  },

  getIncrementals(projectId) {
    return request({
      url: `/projects/${projectId}/incrementals`,
      method: 'get'
    })
  },

  getTrainingPreview(projectId) {
    return request({
      url: `/projects/${projectId}/training/preview`,
      method: 'get'
    })
  },

  syncFlywheelData(projectId) {
    return request({
      url: `/projects/${projectId}/flywheel/sync`,
      method: 'post'
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

  getUploadedChunks(fileId, projectId) {
    return request({
      url: `/upload/chunks/${fileId}`,
      method: 'get',
      params: { projectId },
      silentError: true
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
  },

  getJob(jobId) {
    return request({
      url: `/auto-annotation/jobs/${jobId}`,
      method: 'get'
    })
  },

  getLatestJob(projectId) {
    return request({
      url: `/auto-annotation/projects/${projectId}/jobs/latest`,
      method: 'get'
    })
  },

  cancelJob(jobId) {
    return request({
      url: `/auto-annotation/jobs/${jobId}/cancel`,
      method: 'post'
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

export const autoLabelToModelAPI = {
  parseRequirement(projectId, data) {
    return request({
      url: `/projects/${projectId}/requirements/parse`,
      method: 'post',
      data
    })
  },

  getLatestRequirement(projectId) {
    return request({
      url: `/projects/${projectId}/requirements/latest`,
      method: 'get'
    })
  },

  createDatasetProfile(projectId, data = {}) {
    return request({
      url: `/projects/${projectId}/dataset-profile`,
      method: 'post',
      data
    })
  },

  getLatestDatasetProfile(projectId) {
    return request({
      url: `/projects/${projectId}/dataset-profile/latest`,
      method: 'get'
    })
  },

  previewRoute(projectId, data = {}) {
    return request({
      url: `/projects/${projectId}/model-route/preview`,
      method: 'post',
      data
    })
  },

  getLatestRoute(projectId) {
    return request({
      url: `/projects/${projectId}/model-route/latest`,
      method: 'get'
    })
  },

  createJob(projectId, data = {}) {
    return request({
      url: `/projects/${projectId}/auto-label/jobs`,
      method: 'post',
      data
    })
  },

  listJobs(projectId) {
    return request({
      url: `/projects/${projectId}/auto-label/jobs`,
      method: 'get'
    })
  },

  getJob(projectId, jobId) {
    return request({
      url: `/projects/${projectId}/auto-label/jobs/${jobId}`,
      method: 'get'
    })
  },

  probeJob(projectId, jobId) {
    return request({
      url: `/projects/${projectId}/auto-label/jobs/${jobId}/probe`,
      method: 'post'
    })
  },

  runJob(projectId, jobId) {
    return request({
      url: `/projects/${projectId}/auto-label/jobs/${jobId}/run`,
      method: 'post',
      timeout: 90 * 60 * 1000,
      silentError: true
    })
  },

  syncLabelStudio(projectId, jobId) {
    return request({
      url: `/projects/${projectId}/auto-label/jobs/${jobId}/sync-label-studio`,
      method: 'post'
    })
  },

  listCandidates(projectId, jobId, params = {}) {
    return request({
      url: `/projects/${projectId}/auto-label/jobs/${jobId}/candidates`,
      method: 'get',
      params
    })
  },

  listFusedPredictions(projectId, jobId, params = {}) {
    return request({
      url: `/projects/${projectId}/auto-label/jobs/${jobId}/fused-predictions`,
      method: 'get',
      params
    })
  },

  getAnnotatedVideo(projectId, jobId) {
    return request({
      url: `/projects/${projectId}/auto-label/jobs/${jobId}/annotated-video`,
      method: 'get'
    })
  },

  renderAnnotatedVideo(projectId, jobId) {
    return request({
      url: `/projects/${projectId}/auto-label/jobs/${jobId}/annotated-video`,
      method: 'post',
      timeout: 90 * 60 * 1000,
      silentError: true
    })
  },

  trainFromReviewedLabels(projectId, data = {}) {
    return request({
      url: `/projects/${projectId}/train-from-reviewed-labels`,
      method: 'post',
      data
    })
  },

  listModels(params = {}) {
    return request({
      url: '/models/registry',
      method: 'get',
      params
    })
  },

  syncModels() {
    return request({
      url: '/models/registry/sync',
      method: 'post'
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

export const roundAPI = {
  list(projectId) {
    return request({
      url: `/projects/${projectId}/rounds`,
      method: 'get'
    })
  },

  current(projectId) {
    return request({
      url: `/projects/${projectId}/rounds/current`,
      method: 'get'
    })
  },

  closeCurrent(projectId) {
    return request({
      url: `/projects/${projectId}/rounds/close-current`,
      method: 'post'
    })
  },

  trainingPreview(projectId, roundId) {
    return request({
      url: `/projects/${projectId}/rounds/${roundId}/training-preview`,
      method: 'get'
    })
  },

  triggerRetrain(projectId, roundId, data) {
    return request({
      url: `/projects/${projectId}/rounds/${roundId}/trigger-retrain`,
      method: 'post',
      data
    })
  }
}

export const projectConfigAPI = {
  get(projectId) {
    return request({
      url: `/projects/${projectId}/config`,
      method: 'get'
    })
  },

  update(projectId, data) {
    return request({
      url: `/projects/${projectId}/config`,
      method: 'put',
      data
    })
  }
}

export const edgeSimulatorAPI = {
  deploy(data) {
    return request({
      url: '/edge-simulator/deploy',
      method: 'post',
      data
    })
  },

  rollback(data) {
    return request({
      url: '/edge-simulator/rollback',
      method: 'post',
      data
    })
  },

  inference(formData, options = {}) {
    return request({
      url: '/edge-simulator/inference',
      method: 'post',
      params: options.params,
      data: formData,
      onUploadProgress: options.onUploadProgress
    })
  },

  inferenceAsync(formData, options = {}) {
    return request({
      url: '/edge-simulator/inference-async',
      method: 'post',
      params: options.params,
      data: formData,
      timeout: options.timeout ?? 30 * 60 * 1000,
      onUploadProgress: options.onUploadProgress
    })
  },

  inferenceJob(jobId) {
    return request({
      url: `/edge-simulator/inference-jobs/${jobId}`,
      method: 'get'
    })
  },

  deployments(projectId) {
    return request({
      url: '/edge-simulator/deployments',
      method: 'get',
      params: { projectId }
    })
  },

  inferenceHistory(deploymentId) {
    return request({
      url: '/edge-simulator/inference-history',
      method: 'get',
      params: { deploymentId }
    })
  },

  poolStats(projectId, roundId) {
    return request({
      url: '/edge-simulator/pool-stats',
      method: 'get',
      params: { projectId, roundId }
    })
  }
}

export const inferenceDataPointAPI = {
  list(projectId, params = {}) {
    return request({
      url: `/projects/${projectId}/data-points`,
      method: 'get',
      params
    })
  },

  judge(projectId, roundId) {
    return request({
      url: `/projects/${projectId}/data-points/judge`,
      method: 'post',
      params: { roundId }
    })
  },

  review(id, reviewed = true) {
    return request({
      url: `/inference-data-points/${id}/review`,
      method: 'post',
      data: { reviewed }
    })
  }
}
