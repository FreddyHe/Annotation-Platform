import request from '@/utils/request'

export const feasibilityAPI = {
  // 评估CRUD
  createAssessment(data) {
    return request({
      url: '/feasibility/assessments',
      method: 'post',
      data
    })
  },

  listAssessments(params) {
    return request({
      url: '/feasibility/assessments',
      method: 'get',
      params
    })
  },

  getAssessment(id) {
    return request({
      url: `/feasibility/assessments/${id}`,
      method: 'get'
    })
  },

  deleteAssessment(id) {
    return request({
      url: `/feasibility/assessments/${id}`,
      method: 'delete'
    })
  },

  // 工作流接口
  parseRequirement(id) {
    return request({
      url: `/feasibility/assessments/${id}/parse`,
      method: 'post'
    })
  },

  runOvdTest(id, data) {
    return request({
      url: `/feasibility/assessments/${id}/run-ovd-test`,
      method: 'post',
      data
    })
  },

  evaluate(id) {
    return request({
      url: `/feasibility/assessments/${id}/evaluate`,
      method: 'post'
    })
  },

  searchDatasets(id) {
    return request({
      url: `/feasibility/assessments/${id}/search-datasets`,
      method: 'post'
    })
  },

  estimate(id) {
    return request({
      url: `/feasibility/assessments/${id}/estimate`,
      method: 'post'
    })
  },

  submitUserJudgment(id, data) {
    return request({
      url: `/feasibility/assessments/${id}/user-judgment`,
      method: 'post',
      data
    })
  },

  generatePlan(id) {
    return request({
      url: `/feasibility/assessments/${id}/generate-plan`,
      method: 'post'
    })
  },

  generateAIReport(id) {
    return request({
      url: `/feasibility/assessments/${id}/ai-report`,
      method: 'post'
    })
  },

  getReport(id) {
    return request({
      url: `/feasibility/assessments/${id}/report`,
      method: 'get'
    })
  },

  // 数据查询接口
  getCategories(id) {
    return request({
      url: `/feasibility/assessments/${id}/categories`,
      method: 'get'
    })
  },

  updateCategory(assessmentId, categoryId, data) {
    return request({
      url: `/feasibility/assessments/${assessmentId}/categories/${categoryId}`,
      method: 'put',
      data
    })
  },

  deleteCategory(assessmentId, categoryId) {
    return request({
      url: `/feasibility/assessments/${assessmentId}/categories/${categoryId}`,
      method: 'delete'
    })
  },

  getOvdResults(id) {
    return request({
      url: `/feasibility/assessments/${id}/ovd-results`,
      method: 'get'
    })
  },

  getDatasets(id) {
    return request({
      url: `/feasibility/assessments/${id}/datasets`,
      method: 'get'
    })
  },

  getResourceEstimations(id) {
    return request({
      url: `/feasibility/assessments/${id}/resource-estimations`,
      method: 'get'
    })
  },

  getImplementationPlans(id) {
    return request({
      url: `/feasibility/assessments/${id}/implementation-plans`,
      method: 'get'
    })
  },

  getVlmQualityScores(ovdResultId) {
    return request({
      url: `/feasibility/ovd-results/${ovdResultId}/quality-scores`,
      method: 'get'
    })
  }
}
