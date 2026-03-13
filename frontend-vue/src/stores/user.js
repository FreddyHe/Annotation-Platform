import { defineStore } from 'pinia'
import { authAPI, userAPI } from '@/api'
import { generateDefaultAvatar } from '@/utils/avatar'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    userInfo: JSON.parse(localStorage.getItem('userInfo') || 'null')
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    username: (state) => state.userInfo?.username || '',
    email: (state) => state.userInfo?.email || '',
    displayName: (state) => state.userInfo?.displayName || '',
    organization: (state) => state.userInfo?.organization || null,
    avatarUrl: (state) => {
      if (state.userInfo?.avatarUrl) {
        return state.userInfo.avatarUrl
      }
      return generateDefaultAvatar(state.userInfo?.username || 'User')
    }
  },

  actions: {
    setToken(token) {
      this.token = token
      localStorage.setItem('token', token)
    },

    setUserInfo(userInfo) {
      this.userInfo = userInfo
      localStorage.setItem('userInfo', JSON.stringify(userInfo))
    },

    async login(loginData) {
      const response = await authAPI.login(loginData)
      this.setToken(response.data.token)
      this.setUserInfo(response.data.user)
      return response
    },

    async register(registerData) {
      const response = await authAPI.register(registerData)
      this.setToken(response.data.token)
      this.setUserInfo(response.data.user)
      return response
    },

    async logout() {
      try {
        await authAPI.logout()
      } catch (error) {
        console.error('Logout error:', error)
      } finally {
        this.token = ''
        this.userInfo = null
        localStorage.removeItem('token')
        localStorage.removeItem('userInfo')
      }
    },

    clearUserData() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
    },

    async fetchUserProfile() {
      try {
        const response = await userAPI.getUserProfile()
        this.setUserInfo(response.data)
        return response
      } catch (error) {
        console.error('获取用户资料失败:', error)
        throw error
      }
    }
  }
})
