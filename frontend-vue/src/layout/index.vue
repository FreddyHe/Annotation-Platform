<template>
  <el-container class="layout-container">
    <el-aside :width="isCollapse ? '64px' : '232px'" class="layout-aside">
      <div class="logo" :class="{ 'logo-collapsed': isCollapse }">
        <div class="logo-icon">A</div>
        <transition name="fade">
          <span v-if="!isCollapse" class="logo-text">Annotation</span>
        </transition>
      </div>
      <el-menu
        :default-active="activeMenu"
        class="layout-menu"
        router
        :collapse="isCollapse"
        :collapse-transition="false"
      >
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <template #title>概览</template>
        </el-menu-item>
        <el-menu-item index="/projects">
          <el-icon><FolderOpened /></el-icon>
          <template #title>项目管理</template>
        </el-menu-item>
        <el-menu-item index="/model-training">
          <el-icon><Cpu /></el-icon>
          <template #title>单类别模型训练</template>
        </el-menu-item>
        <el-menu-item index="/single-class-detection">
          <el-icon><Monitor /></el-icon>
          <template #title>单类别检测</template>
        </el-menu-item>
        <el-menu-item index="/feasibility">
          <el-icon><DataAnalysis /></el-icon>
          <template #title>可行性评估</template>
        </el-menu-item>
      </el-menu>
    </el-aside>
    
    <el-container class="layout-main">
      <el-header class="layout-header" height="56px">
        <div class="header-left">
          <button class="toggle-btn" @click="toggleSidebar">
            <el-icon :size="18"><Fold v-if="!isCollapse" /><Expand v-else /></el-icon>
          </button>
        </div>
        <div class="header-right">
          <el-dropdown @command="handleCommand" trigger="click">
            <div class="user-dropdown">
              <el-avatar :size="30" :src="userAvatar" class="user-avatar">
                {{ displayName.charAt(0).toUpperCase() }}
              </el-avatar>
              <span class="username">{{ displayName }}</span>
              <el-icon class="el-icon--right" :size="12"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">
                  <el-icon><User /></el-icon>
                  个人中心
                </el-dropdown-item>
                <el-dropdown-item command="settings">
                  <el-icon><Setting /></el-icon>
                  设置
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      
      <el-main class="layout-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isCollapse = ref(false)

const activeMenu = computed(() => route.path)
const displayName = computed(() => userStore.displayName || '用户')
const userAvatar = computed(() => userStore.avatarUrl)

const toggleSidebar = () => {
  isCollapse.value = !isCollapse.value
}

const handleCommand = (command) => {
  switch (command) {
    case 'profile':
      router.push('/profile')
      break
    case 'settings':
      router.push('/settings')
      break
    case 'logout':
      handleLogout()
      break
  }
}

const handleLogout = () => {
  ElMessageBox.confirm('确定要退出登录吗？', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    await userStore.logout()
    router.push('/login')
  }).catch(() => {})
}

onMounted(async () => {
  try {
    await userStore.fetchUserProfile()
  } catch (error) {
    console.error('加载用户资料失败:', error)
  }
})
</script>

<style scoped>
.layout-container {
  height: 100vh;
}

.layout-aside {
  background-color: #fff;
  border-right: 0.5px solid var(--gray-200);
  overflow-x: hidden;
  overflow-y: auto;
  transition: width 0.25s var(--ease-out);
  display: flex;
  flex-direction: column;
}

.logo {
  height: var(--header-height);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  border-bottom: 0.5px solid var(--gray-200);
  flex-shrink: 0;
}

.logo-collapsed {
  justify-content: center;
  padding: 0;
}

.logo-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-md);
  background: var(--brand-600);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  font-size: 13px;
  flex-shrink: 0;
}

.logo-text {
  font-size: 15px;
  font-weight: 600;
  color: var(--gray-900);
  letter-spacing: -0.02em;
  white-space: nowrap;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.layout-menu {
  border-right: none !important;
  padding: 8px;
  flex: 1;
}

.layout-menu .el-menu-item {
  height: 40px;
  line-height: 40px;
  border-radius: var(--radius-md);
  margin-bottom: 2px;
  color: var(--gray-500);
  font-size: 13px;
  font-weight: 500;
  transition: all 0.15s var(--ease-out);
}

.layout-menu .el-menu-item:hover {
  background-color: var(--gray-50);
  color: var(--gray-700);
}

.layout-menu .el-menu-item.is-active {
  background-color: var(--brand-50);
  color: var(--brand-800);
}

.layout-menu .el-menu-item .el-icon {
  font-size: 16px;
  margin-right: 8px;
}

.layout-main {
  background-color: var(--gray-100);
}

.layout-header {
  background-color: #fff;
  border-bottom: 0.5px solid var(--gray-200);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
  height: var(--header-height);
}

.header-left {
  display: flex;
  align-items: center;
}

.toggle-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-md);
  border: none;
  background: transparent;
  color: var(--gray-500);
  cursor: pointer;
  transition: all 0.15s;
}

.toggle-btn:hover {
  background-color: var(--gray-100);
  color: var(--gray-700);
}

.header-right {
  display: flex;
  align-items: center;
}

.user-dropdown {
  display: flex;
  align-items: center;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-md);
  transition: background-color 0.15s;
}

.user-dropdown:hover {
  background-color: var(--gray-50);
}

.user-avatar {
  background-color: var(--brand-50) !important;
  color: var(--brand-800) !important;
  font-weight: 500;
  font-size: 12px;
}

.username {
  margin: 0 6px 0 10px;
  font-size: 13px;
  font-weight: 500;
  color: var(--gray-700);
}

.layout-content {
  padding: 24px;
  overflow-y: auto;
}
</style>
