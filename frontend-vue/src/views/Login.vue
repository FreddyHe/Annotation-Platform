<template>
  <main class="login-page">
    <section class="login-panel">
      <div class="login-brand-row">
        <div class="login-brand">
          <MiniBrandMark />
          <span>
            <strong>多智能体标注平台</strong>
            <small>INTELLIGENT DATA OPERATIONS</small>
          </span>
        </div>
        <label class="locale-select">
          <span>语言</span>
          <select aria-label="语言" disabled><option>简体中文</option></select>
        </label>
      </div>

      <div class="login-form-wrap">
        <div class="login-intro">
          <span class="section-index">ENTERPRISE SECURE ACCESS</span>
          <h1>{{ activeTab === 'login' ? '欢迎回来' : '创建平台账户' }}</h1>
          <p>{{ activeTab === 'login' ? '登录智能标注、训练与模型闭环平台' : '注册后即可创建项目并管理标注流程' }}</p>
        </div>

        <el-form
          v-if="activeTab === 'login'"
          ref="loginFormRef"
          :model="loginForm"
          :rules="loginRules"
          class="login-form"
          label-position="top"
          @submit.prevent="handleLogin"
        >
          <el-form-item label="账号" prop="username">
            <el-input
              v-model="loginForm.username"
              placeholder="请输入账号"
              prefix-icon="User"
              size="large"
              autocomplete="username"
              clearable
            />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="loginForm.password"
              type="password"
              placeholder="请输入密码"
              prefix-icon="Lock"
              size="large"
              autocomplete="current-password"
              show-password
              clearable
              @keyup.enter="handleLogin"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="large" :loading="loginLoading" class="submit-button" @click="handleLogin">
              <span>安全登录</span><span class="button-arrow">→</span>
            </el-button>
          </el-form-item>
          <button class="mode-switch" type="button" @click="activeTab = 'register'">
            没有账号？<strong>注册新账户</strong>
          </button>
        </el-form>

        <el-form
          v-else
          ref="registerFormRef"
          :model="registerForm"
          :rules="registerRules"
          class="login-form register-form"
          label-position="top"
          @submit.prevent="handleRegister"
        >
          <div class="register-grid">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="registerForm.username" placeholder="请输入用户名" prefix-icon="User" size="large" clearable />
            </el-form-item>
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="registerForm.email" placeholder="请输入邮箱" prefix-icon="Message" size="large" clearable />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="registerForm.password" type="password" placeholder="至少 6 位" prefix-icon="Lock" size="large" show-password clearable />
            </el-form-item>
            <el-form-item label="显示名称" prop="displayName">
              <el-input v-model="registerForm.displayName" placeholder="请输入显示名称" prefix-icon="Avatar" size="large" clearable />
            </el-form-item>
          </div>
          <el-form-item label="组织名称（可选）" prop="organizationName">
            <el-input v-model="registerForm.organizationName" placeholder="请输入组织名称" prefix-icon="OfficeBuilding" size="large" clearable />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="large" :loading="registerLoading" class="submit-button" @click="handleRegister">
              <span>创建账户</span><span class="button-arrow">→</span>
            </el-button>
          </el-form-item>
          <button class="mode-switch" type="button" @click="activeTab = 'login'">
            已有账号？<strong>返回登录</strong>
          </button>
        </el-form>

        <div class="login-footnote">
          <span><i /> 加密连接</span>
          <span>账户与项目数据隔离</span>
        </div>
      </div>
    </section>

    <section class="visual-panel" aria-label="平台能力概览">
      <div class="grid-lines" />
      <div class="signal-orbit orbit-one" />
      <div class="signal-orbit orbit-two" />
      <span class="signal-dot dot-one" />
      <span class="signal-dot dot-two" />

      <div class="visual-copy">
        <span class="visual-kicker">DATA TO MODEL · MODEL TO EDGE</span>
        <h2>让每一份数据<br />持续进化为<br />可部署的智能</h2>
        <p>统一智能标注、人工复核、模型训练、边端验证与反馈再训练，构建完整的数据闭环。</p>
      </div>

      <div class="capability-list">
        <article>
          <strong>01</strong>
          <div><b>智能标注</b><span>多模型路由、融合与人工复核</span></div>
        </article>
        <article>
          <strong>02</strong>
          <div><b>模型训练</b><span>训练、测试与模型版本管理</span></div>
        </article>
        <article>
          <strong>03</strong>
          <div><b>闭环迭代</b><span>边端验证、回流再训练与回滚</span></div>
        </article>
      </div>

      <div class="visual-status"><i /> 企业服务 · 安全运行</div>
    </section>
  </main>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'
import MiniBrandMark from '@/components/platform-ui/MiniBrandMark.vue'

const router = useRouter()
const userStore = useUserStore()
const activeTab = ref('login')
const loginLoading = ref(false)
const registerLoading = ref(false)
const loginFormRef = ref(null)
const registerFormRef = ref(null)

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({ username: '', email: '', password: '', displayName: '', organizationName: '' })

const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const registerRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '用户名长度在 3 到 50 个字符', trigger: 'blur' }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 100, message: '密码长度在 6 到 100 个字符', trigger: 'blur' }
  ],
  displayName: [{ required: true, message: '请输入显示名称', trigger: 'blur' }]
}

const handleLogin = async () => {
  if (!loginFormRef.value) return
  await loginFormRef.value.validate(async (valid) => {
    if (!valid) return
    loginLoading.value = true
    try {
      await userStore.login(loginForm)
      ElMessage.success('登录成功')
      router.push('/dashboard')
    } catch (error) {
      ElMessage.error(error.message || '登录失败')
    } finally {
      loginLoading.value = false
    }
  })
}

const handleRegister = async () => {
  if (!registerFormRef.value) return
  await registerFormRef.value.validate(async (valid) => {
    if (!valid) return
    registerLoading.value = true
    try {
      await userStore.register(registerForm)
      ElMessage.success('注册成功')
      router.push('/dashboard')
    } catch (error) {
      ElMessage.error(error.message || '注册失败')
    } finally {
      registerLoading.value = false
    }
  })
}
</script>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: minmax(520px, 0.9fr) minmax(680px, 1.1fr);
  min-height: 100vh;
  background: #fff;
}

.login-panel {
  position: relative;
  display: grid;
  grid-template-rows: auto 1fr;
  min-height: 100vh;
  padding: 34px 7vw 44px;
}

.login-brand-row,
.login-brand,
.locale-select,
.login-footnote,
.capability-list article {
  display: flex;
  align-items: center;
}

.login-brand-row {
  justify-content: space-between;
}

.login-brand {
  gap: 12px;
}

.login-brand > span:last-child {
  display: grid;
}

.login-brand strong {
  color: var(--ink-950);
  font-size: 15px;
}

.login-brand small {
  margin-top: 3px;
  color: #8c9bb0;
  font-size: 7px;
  font-weight: 800;
  letter-spacing: 0.16em;
}

.locale-select {
  gap: 8px;
  color: var(--ink-500);
  font-size: 10px;
}

.locale-select select {
  min-width: 150px;
  min-height: 36px;
  padding: 0 30px 0 12px;
  color: var(--ink-700);
  background: #f7f9fc;
  border: 1px solid #e1e8f1;
  border-radius: 10px;
}

.login-form-wrap {
  width: min(100%, 440px);
  margin: auto;
  animation: rise 550ms cubic-bezier(.22, 1, .36, 1) both;
}

.section-index,
.visual-kicker {
  color: var(--brand-600);
  font-size: 10px;
  font-weight: 850;
  letter-spacing: 0.16em;
}

.login-intro h1 {
  margin: 15px 0 8px;
  color: var(--ink-950);
  font-size: clamp(38px, 4vw, 50px);
  line-height: 1.08;
  letter-spacing: -0.05em;
}

.login-intro p {
  margin: 0;
  color: var(--ink-500);
  font-size: 14px;
}

.login-form {
  display: grid;
  gap: 3px;
  margin-top: 38px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.login-form :deep(.el-form-item__label) {
  height: auto;
  padding: 0 0 8px;
  color: var(--ink-700);
  font-size: 12px;
  font-weight: 800;
  line-height: 1.4;
}

.login-form :deep(.el-input__wrapper) {
  min-height: 52px;
  padding: 0 15px;
  background: #f7f9fc !important;
  border-radius: 12px !important;
}

.login-form :deep(.el-input__inner) {
  font-size: 14px;
}

.register-form {
  margin-top: 26px;
}

.register-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}

.submit-button {
  display: flex;
  width: 100%;
  min-height: 54px;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  font-size: 15px;
  border-radius: 12px !important;
}

.button-arrow {
  font-size: 21px;
  font-weight: 400;
}

.mode-switch {
  justify-self: center;
  padding: 5px;
  color: var(--ink-500);
  font-size: 12px;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.mode-switch strong {
  color: var(--brand-600);
}

.login-footnote {
  justify-content: space-between;
  margin-top: 16px;
  color: #8595aa;
  font-size: 9px;
}

.login-footnote span:first-child {
  display: flex;
  gap: 8px;
  align-items: center;
}

.login-footnote i,
.visual-status i {
  width: 6px;
  height: 6px;
  background: #24c78a;
  border-radius: 50%;
  box-shadow: 0 0 0 4px rgba(36, 199, 138, 0.08);
}

.visual-panel {
  position: relative;
  min-height: calc(100vh - 28px);
  margin: 14px 14px 14px 0;
  overflow: hidden;
  color: #fff;
  background: linear-gradient(145deg, #061a36, #082b5c);
  border-radius: 24px;
  box-shadow: var(--shadow-lg);
}

.grid-lines {
  position: absolute;
  inset: 0;
  opacity: 0.13;
  background-image: linear-gradient(rgba(90, 160, 235, .35) 1px, transparent 1px), linear-gradient(90deg, rgba(90, 160, 235, .35) 1px, transparent 1px);
  background-size: 72px 72px;
}

.signal-orbit {
  position: absolute;
  right: -8%;
  top: 38%;
  border: 1px solid rgba(73, 153, 243, 0.28);
  border-radius: 50%;
}

.orbit-one { width: 420px; height: 420px; }
.orbit-two { right: 5%; top: 49%; width: 180px; height: 180px; }

.signal-dot {
  position: absolute;
  width: 13px;
  height: 13px;
  background: #4ca2ff;
  border-radius: 50%;
  box-shadow: 0 0 18px rgba(76, 162, 255, .8);
}

.dot-one { top: 44%; right: 28%; }
.dot-two { top: 51%; right: 20%; width: 10px; height: 10px; }

.visual-copy,
.capability-list,
.visual-status {
  position: absolute;
  z-index: 1;
  right: 12%;
  left: 12%;
}

.visual-copy {
  top: 10%;
}

.visual-copy h2 {
  margin: 24px 0 28px;
  font-size: clamp(48px, 5.2vw, 76px);
  line-height: 1.03;
  letter-spacing: -0.055em;
}

.visual-copy p {
  width: min(560px, 78%);
  margin: 0;
  color: #a9bad0;
  font-size: 15px;
  line-height: 1.9;
}

.capability-list {
  bottom: 11%;
  width: min(520px, 70%);
}

.capability-list article {
  gap: 24px;
  padding: 17px 0;
  border-top: 1px solid rgba(166, 194, 226, 0.25);
}

.capability-list article > strong {
  color: #4ca2ff;
  font-size: 12px;
}

.capability-list article div {
  display: grid;
}

.capability-list b {
  font-size: 13px;
}

.capability-list span {
  margin-top: 2px;
  color: #7f96b2;
  font-size: 10px;
}

.visual-status {
  bottom: 28px;
  left: auto;
  display: flex;
  gap: 10px;
  align-items: center;
  width: auto;
  color: #7f96b2;
  font-size: 8px;
  font-weight: 800;
  letter-spacing: 0.16em;
}

@keyframes rise {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 1050px) {
  .login-page { grid-template-columns: 1fr; }
  .visual-panel { display: none; }
  .login-panel { padding-right: 9vw; padding-left: 9vw; }
}

@media (max-width: 560px) {
  .login-panel { padding: 24px; }
  .locale-select span { display: none; }
  .locale-select select { min-width: 110px; }
  .register-grid { grid-template-columns: 1fr; }
  .login-intro h1 { font-size: 38px; }
}
</style>
