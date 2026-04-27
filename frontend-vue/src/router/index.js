import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', requiresAuth: false }
  },
  {
    path: '/',
    name: 'Layout',
    component: () => import('@/layout/index.vue'),
    redirect: '/dashboard',
    meta: { requiresAuth: true },
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '概览', icon: 'Odometer' }
      },
      {
        path: 'projects',
        name: 'ProjectList',
        component: () => import('@/views/ProjectList.vue'),
        meta: { title: '项目管理', icon: 'FolderOpened' }
      },
      {
        path: 'projects/:id',
        name: 'ProjectDetail',
        component: () => import('@/views/ProjectDetail.vue'),
        meta: { title: '项目详情', icon: 'Document' }
      },
      {
        path: 'model-training',
        name: 'ModelTraining',
        component: () => import('@/views/SingleClassWorkflow.vue'),
        meta: { title: '单类别训练与检测', icon: 'Cpu' }
      },
      {
        path: 'single-class-detection',
        name: 'SingleClassDetection',
        component: () => import('@/views/SingleClassWorkflow.vue'),
        meta: { title: '单类别检测', icon: 'Monitor' }
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/Profile.vue'),
        meta: { title: '个人中心', icon: 'User' }
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/Settings.vue'),
        meta: { title: '设置', icon: 'Setting' }
      },
      {
        path: 'feasibility',
        name: 'AssessmentList',
        component: () => import('@/views/feasibility/AssessmentList.vue'),
        meta: { title: '可行性评估', icon: 'DataAnalysis' }
      },
      {
        path: 'feasibility/create',
        name: 'CreateAssessment',
        component: () => import('@/views/feasibility/CreateAssessment.vue'),
        meta: { title: '新建评估', icon: 'Plus' }
      },
      {
        path: 'feasibility/:id',
        name: 'AssessmentDetail',
        component: () => import('@/views/feasibility/AssessmentDetail.vue'),
        meta: { title: '评估详情', icon: 'Document' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - Annotation Platform` : 'Annotation Platform'
  
  const userStore = useUserStore()
  const token = localStorage.getItem('token')
  
  if (to.meta.requiresAuth !== false && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next('/dashboard')
  } else {
    next()
  }
})

export default router
