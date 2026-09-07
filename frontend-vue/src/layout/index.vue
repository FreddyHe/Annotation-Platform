<template>
  <div class="app-shell" :class="{ 'is-collapsed': isCollapse }">
    <aside class="sidebar">
      <div class="brand" :class="{ compact: isCollapse }">
        <BrandMark />
        <span v-if="!isCollapse" class="brand-copy">
          <strong>多智能体标注平台</strong>
          <small>INTELLIGENT DATA OPERATIONS</small>
        </span>
      </div>

      <div class="sidebar-scroll">
        <nav class="primary-nav" aria-label="主导航">
          <section
            v-for="group in navigationGroups"
            :key="group.label"
            class="nav-group"
            :class="{ 'is-active': isGroupActive(group) }"
          >
            <div class="nav-group-heading">
              <span class="group-icon"><NavigationIcon :name="group.icon" /></span>
              <span v-if="!isCollapse" class="group-copy">
                <strong>{{ group.label }}</strong>
                <small>{{ group.caption }}</small>
              </span>
              <span v-if="!isCollapse" class="group-count">{{ group.items.length }}</span>
            </div>

            <div class="nav-group-items">
              <router-link
                v-for="item in group.items"
                :key="item.path"
                :to="item.path"
                class="nav-item"
                :class="{ active: isItemActive(item.path) }"
                :title="isCollapse ? item.label : undefined"
              >
                <span class="nav-icon"><NavigationIcon :name="item.icon" /></span>
                <span v-if="!isCollapse" class="nav-copy">
                  <strong>{{ item.label }}</strong>
                  <small>{{ item.caption }}</small>
                </span>
                <span v-if="!isCollapse" class="nav-arrow">›</span>
              </router-link>
            </div>
          </section>
        </nav>
      </div>

      <div class="sidebar-footer" :class="{ compact: isCollapse }">
        <span class="status-dot" />
        <span v-if="!isCollapse">
          <strong>安全运行中</strong>
          <small>智能标注 · 模型闭环</small>
        </span>
      </div>
    </aside>

    <section class="workspace">
      <header class="topbar">
        <div class="topbar-title">
          <button class="toggle-btn" type="button" aria-label="切换侧栏" @click="toggleSidebar">
            <el-icon><Fold v-if="!isCollapse" /><Expand v-else /></el-icon>
          </button>
          <div>
            <span class="breadcrumb">智能数据闭环平台 /</span>
            <h1>{{ pageTitle }}</h1>
          </div>
        </div>

        <div class="topbar-actions">
          <span class="connection-pill"><i /> 平台在线</span>
          <span class="locale-display">简体中文 <el-icon><ArrowDown /></el-icon></span>
          <el-dropdown trigger="click" @command="handleCommand">
            <button class="user-dropdown" type="button">
              <span class="user-avatar">{{ displayName.charAt(0).toUpperCase() }}</span>
              <span class="user-copy">
                <strong>{{ displayName }}</strong>
                <small>{{ userStore.username || 'USER' }}</small>
              </span>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile"><el-icon><User /></el-icon>个人中心</el-dropdown-item>
                <el-dropdown-item command="settings"><el-icon><Setting /></el-icon>设置</el-dropdown-item>
                <el-dropdown-item divided command="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <button class="logout-btn" type="button" @click="handleLogout">退出</button>
        </div>
      </header>

      <main class="layout-content">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessageBox } from 'element-plus'
import BrandMark from '@/components/platform-ui/BrandMark.vue'
import NavigationIcon from '@/components/platform-ui/NavigationIcon.vue'
import {
  ArrowDown,
  Expand,
  Fold,
  SwitchButton,
  User
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const isCollapse = ref(false)

const navigationGroups = [
  {
    label: '数据工作台',
    caption: '项目、数据与标注流程',
    icon: 'video-group',
    items: [
      { path: '/dashboard', label: '运营概览', caption: '项目与任务运行态势', icon: 'overview' },
      { path: '/projects', label: '项目管理', caption: '智能标注与数据复核', icon: 'resources' }
    ]
  },
  {
    label: '平台管理',
    caption: '账号、配置与服务接入',
    icon: 'administration-group',
    items: [
      { path: '/profile', label: '个人中心', caption: '账户与组织信息', icon: 'identity' },
      { path: '/settings', label: '平台设置', caption: '模型与接口配置', icon: 'administration-group' }
    ]
  }
]

const pageTitle = computed(() => route.meta?.title || '智能标注平台')
const displayName = computed(() => userStore.displayName || '平台用户')

const isItemActive = (path) => {
  if (path === '/projects') return route.path === '/projects' || route.path.startsWith('/projects/')
  return route.path === path || route.path.startsWith(`${path}/`)
}

const isGroupActive = (group) => group.items.some(item => isItemActive(item.path))
const toggleSidebar = () => { isCollapse.value = !isCollapse.value }

const handleCommand = (command) => {
  if (command === 'profile') router.push('/profile')
  if (command === 'settings') router.push('/settings')
  if (command === 'logout') handleLogout()
}

const handleLogout = () => {
  ElMessageBox.confirm('确定要退出当前账号吗？', '退出登录', {
    confirmButtonText: '退出',
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
.app-shell {
  display: grid;
  grid-template-columns: 288px minmax(0, 1fr);
  min-height: 100vh;
  background: var(--canvas);
  transition: grid-template-columns 220ms ease;
}

.app-shell.is-collapsed {
  grid-template-columns: 82px minmax(0, 1fr);
}

.sidebar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  background: var(--surface);
  border-right: 1px solid var(--line);
}

.brand {
  display: flex;
  gap: 13px;
  align-items: center;
  height: 82px;
  padding: 0 22px;
  flex: 0 0 auto;
}

.brand.compact {
  justify-content: center;
  padding: 0;
}

.brand-copy,
.group-copy,
.nav-copy,
.user-copy {
  display: grid;
  min-width: 0;
}

.brand-copy strong {
  color: var(--ink-950);
  font-size: 16px;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.brand-copy small {
  margin-top: 3px;
  color: #8b9aaf;
  font-size: 7px;
  font-weight: 800;
  letter-spacing: 0.14em;
  white-space: nowrap;
}

.sidebar-scroll {
  min-height: 0;
  overflow-y: auto;
}

.primary-nav {
  display: grid;
  gap: 10px;
  padding: 8px 14px 18px;
}

.nav-group {
  overflow: hidden;
  border: 1px solid transparent;
  border-radius: 16px;
}

.nav-group.is-active {
  background: linear-gradient(145deg, #f3f8ff, #edf5ff);
  border-color: #d7e7fb;
  box-shadow: 0 8px 24px rgba(17, 72, 142, 0.06);
}

.nav-group-heading {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  gap: 11px;
  align-items: center;
  min-height: 64px;
  padding: 9px 11px;
}

.group-icon {
  display: grid;
  width: 38px;
  height: 38px;
  color: #406287;
  background: #eef3f9;
  border-radius: 12px;
  place-items: center;
}

.nav-group.is-active .group-icon {
  color: #fff;
  background: linear-gradient(145deg, var(--brand-800), var(--brand-600));
  box-shadow: 0 8px 18px rgba(23, 107, 255, 0.2);
}

.group-icon :deep(svg),
.nav-icon :deep(svg) {
  width: 19px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}

.group-copy strong,
.nav-copy strong {
  overflow: hidden;
  color: var(--ink-950);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.group-copy small,
.nav-copy small {
  margin-top: 2px;
  overflow: hidden;
  color: var(--ink-500);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.group-count {
  display: grid;
  width: 22px;
  height: 22px;
  color: #7b8ca2;
  font-size: 10px;
  font-weight: 800;
  background: #eaf0f7;
  border-radius: 999px;
  place-items: center;
}

.nav-group-items {
  position: relative;
  display: grid;
  gap: 3px;
  padding: 0 8px 10px 16px;
}

.nav-group-items::before {
  position: absolute;
  top: 0;
  bottom: 15px;
  left: 29px;
  width: 1px;
  content: '';
  background: #d9e4f0;
}

.nav-item {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  min-height: 50px;
  padding: 7px 10px;
  color: var(--ink-700);
  border-radius: 11px;
}

.nav-item:hover {
  color: var(--brand-800);
  background: rgba(255, 255, 255, 0.7);
}

.nav-item.active {
  color: var(--brand-800);
  background: #fff;
  box-shadow: 0 7px 18px rgba(23, 67, 126, 0.08);
}

.nav-item.active::before {
  position: absolute;
  left: -4px;
  width: 6px;
  height: 6px;
  content: '';
  background: var(--brand-600);
  border-radius: 50%;
  box-shadow: 0 0 0 4px #dceaff;
}

.nav-icon {
  display: grid;
  color: var(--brand-700);
  place-items: center;
}

.nav-arrow {
  color: #8da0b8;
  font-size: 18px;
}

.is-collapsed .nav-group-heading {
  grid-template-columns: 38px;
  justify-content: center;
  padding: 8px 0;
}

.is-collapsed .nav-group-items {
  padding: 0 0 10px;
}

.is-collapsed .nav-group-items::before {
  display: none;
}

.is-collapsed .nav-item {
  grid-template-columns: 34px;
  justify-content: center;
  margin: 0 5px;
  padding: 8px 0;
}

.sidebar-footer {
  display: flex;
  gap: 10px;
  align-items: center;
  min-height: 82px;
  padding: 14px 22px;
  border-top: 1px solid var(--line);
}

.sidebar-footer.compact {
  justify-content: center;
  padding: 0;
}

.sidebar-footer > span:last-child {
  display: grid;
}

.sidebar-footer strong {
  font-size: 11px;
}

.sidebar-footer small {
  margin-top: 3px;
  color: var(--ink-500);
  font-size: 9px;
}

.status-dot,
.connection-pill i {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  background: #18a873;
  border-radius: 50%;
  box-shadow: 0 0 0 5px rgba(24, 168, 115, 0.1);
}

.workspace {
  min-width: 0;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 15;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 82px;
  padding: 0 30px;
  background: rgba(255, 255, 255, 0.96);
  border-bottom: 1px solid var(--line);
  backdrop-filter: blur(14px);
}

.topbar-title,
.topbar-actions,
.user-dropdown,
.connection-pill {
  display: flex;
  align-items: center;
}

.locale-display {
  min-width: 154px;
  height: 38px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--ink-700);
  font-size: 12px;
  background: #f8fafd;
  border: 1px solid var(--line);
  border-radius: 10px;
}

.logout-btn {
  height: 38px;
  padding: 0 14px;
  color: var(--ink-700);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 10px;
}

.logout-btn:hover {
  color: var(--brand-700);
  background: var(--brand-50);
  border-color: #a9c9f8;
}

.topbar-title {
  gap: 14px;
}

.topbar-title > div {
  display: grid;
}

.breadcrumb {
  color: #8797aa;
  font-size: 10px;
}

.topbar h1 {
  margin: 3px 0 0;
  color: var(--ink-950);
  font-size: 21px;
  letter-spacing: -0.03em;
}

.toggle-btn {
  display: grid;
  width: 38px;
  height: 38px;
  color: var(--ink-700);
  cursor: pointer;
  background: #f5f8fc;
  border: 1px solid var(--line);
  border-radius: 11px;
  place-items: center;
}

.toggle-btn:hover {
  color: var(--brand-700);
  background: var(--brand-50);
}

.topbar-actions {
  gap: 18px;
}

.connection-pill {
  gap: 8px;
  padding: 8px 12px;
  color: #16805d;
  font-size: 11px;
  font-weight: 800;
  background: #edf9f5;
  border-radius: 999px;
}

.user-dropdown {
  gap: 10px;
  padding: 4px 8px 4px 4px;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 12px;
}

.user-dropdown:hover {
  background: #f5f8fc;
}

.user-avatar {
  display: grid;
  width: 38px;
  height: 38px;
  color: #fff;
  font-size: 14px;
  font-weight: 800;
  background: var(--brand-900);
  border-radius: 12px;
  place-items: center;
}

.user-copy strong {
  color: var(--ink-950);
  font-size: 12px;
  text-align: left;
}

.user-copy small {
  color: var(--ink-500);
  font-size: 8px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-align: left;
  text-transform: uppercase;
}

.layout-content {
  min-height: calc(100vh - 82px);
  padding: 38px 32px 48px;
  overflow-x: hidden;
}

.layout-content > :deep(*) {
  width: 100%;
  max-width: 1540px;
  margin-right: auto;
  margin-left: auto;
}

.page-enter-active,
.page-leave-active {
  transition: opacity 180ms ease, transform 180ms ease;
}

.page-enter-from,
.page-leave-to {
  opacity: 0;
  transform: translateY(5px);
}

@media (max-width: 900px) {
  .app-shell,
  .app-shell.is-collapsed {
    grid-template-columns: 82px minmax(0, 1fr);
  }
  .brand-copy,
  .group-copy,
  .nav-copy,
  .group-count,
  .nav-arrow,
  .sidebar-footer span:last-child {
    display: none;
  }
  .nav-group-heading,
  .nav-item {
    grid-template-columns: 38px;
    justify-content: center;
  }
  .nav-group-items::before {
    display: none;
  }
  .topbar {
    padding: 0 18px;
  }
  .connection-pill,
  .locale-display,
  .logout-btn,
  .user-copy {
    display: none;
  }
  .layout-content {
    padding: 26px 18px 36px;
  }
}
</style>
