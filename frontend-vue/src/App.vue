<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<style>
:root {
  --brand-500: #2f7cff;
  --brand-400: #4ca2ff;
  --brand-300: #8fc1ff;
  --brand-200: #bcd8ff;

  --gray-50: #f8fafd;
  --gray-100: #f4f7fb;
  --gray-200: #dfe7f1;
  --gray-300: #c7d3e2;
  --gray-400: #9babbe;
  --gray-500: #73839a;
  --gray-600: #566a86;
  --gray-700: #415572;
  --gray-800: #263d5c;
  --gray-900: #10233f;

  --success-bg: #e9f8f2;
  --success-text: #137957;
  --success-border: #9bdcc5;
  --warning-bg: #fff6e7;
  --warning-text: #a0640b;
  --warning-border: #f3ca82;
  --danger-bg: #fff0f2;
  --danger-text: #b83748;
  --danger-border: #f3a9b2;
  --info-bg: #edf5ff;
  --info-text: #0c4f9f;
  --info-border: #a9c9f8;

  --ink-800: #263d5c;
  --sidebar-width: 288px;
  --header-height: 82px;
  --radius-sm: 8px;
  --radius-md: 10px;
  --radius-lg: 15px;
  --radius-xl: 20px;
  --ease-out: cubic-bezier(0.16, 1, 0.3, 1);

  --el-color-primary: #176bff;
  --el-color-primary-light-3: #4ca2ff;
  --el-color-primary-light-5: #8fc1ff;
  --el-color-primary-light-7: #bcd8ff;
  --el-color-primary-light-8: #dceaff;
  --el-color-primary-light-9: #edf5ff;
  --el-color-primary-dark-2: #0c3f82;
  --el-color-success: #18a873;
  --el-color-warning: #d98b18;
  --el-color-danger: #d94b5b;
  --el-border-radius-base: 10px;
  --el-border-radius-small: 8px;
  --el-border-radius-round: 999px;
  --el-border-color: #dfe7f1;
  --el-border-color-light: #e7edf5;
  --el-border-color-lighter: #eef3f8;
  --el-bg-color: #fff;
  --el-bg-color-page: #f4f7fb;
  --el-fill-color-light: #f8fafd;
  --el-fill-color-lighter: #f4f7fb;
  --el-text-color-primary: #10233f;
  --el-text-color-regular: #415572;
  --el-text-color-secondary: #73839a;
  --el-text-color-placeholder: #9babbe;
  --el-font-family: "HarmonyOS Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif;
  --el-font-size-base: 14px;
}

body {
  font-size: 14px;
  line-height: 1.6;
  -moz-osx-font-smoothing: grayscale;
}

textarea,
select {
  font: inherit;
}

.el-card {
  overflow: hidden;
  background: var(--surface) !important;
  border: 1px solid var(--line) !important;
  border-radius: var(--radius-lg) !important;
  box-shadow: var(--shadow-sm) !important;
  transition: border-color 180ms ease, box-shadow 180ms ease, transform 180ms ease;
}

.el-card:hover {
  border-color: #cbd9e9 !important;
}

.el-card__header {
  padding: 18px 20px !important;
  font-weight: 700;
  border-bottom: 1px solid #e7edf5 !important;
}

.el-card__body {
  padding: 20px !important;
}

.el-button {
  min-height: 36px;
  padding: 8px 15px;
  font-weight: 700;
  border-radius: var(--radius-md) !important;
  transition: all 150ms ease;
}

.el-button--primary {
  color: #fff !important;
  background: var(--brand-600) !important;
  border-color: var(--brand-600) !important;
  box-shadow: 0 8px 18px rgba(23, 107, 255, 0.17);
}

.el-button--primary:hover,
.el-button--primary:focus {
  color: #fff !important;
  background: var(--brand-700) !important;
  border-color: var(--brand-700) !important;
  transform: translateY(-1px);
}

.el-button--default {
  color: var(--ink-700) !important;
  background: #fff !important;
  border-color: var(--line) !important;
}

.el-button--default:hover {
  color: var(--brand-700) !important;
  background: var(--brand-50) !important;
  border-color: #a9c9f8 !important;
}

.el-button.is-link,
.el-button.is-text {
  min-height: auto;
  padding-right: 4px;
  padding-left: 4px;
  color: var(--brand-600) !important;
  background: transparent !important;
  box-shadow: none;
}

.el-input__wrapper,
.el-select__wrapper,
.el-textarea__inner {
  background: #f8fafd !important;
  border-radius: var(--radius-md) !important;
  box-shadow: 0 0 0 1px var(--line) inset !important;
  transition: box-shadow 150ms ease, background 150ms ease;
}

.el-input__wrapper {
  min-height: 40px;
  padding: 1px 13px;
}

.el-input__wrapper:hover,
.el-select__wrapper:hover,
.el-textarea__inner:hover {
  box-shadow: 0 0 0 1px #b7c6d8 inset !important;
}

.el-input__wrapper.is-focus,
.el-select__wrapper.is-focused,
.el-textarea__inner:focus {
  background: #fff !important;
  box-shadow: 0 0 0 1.5px var(--brand-600) inset, 0 0 0 4px rgba(23, 107, 255, 0.08) !important;
}

.el-table {
  --el-table-border-color: #e7edf5;
  --el-table-header-bg-color: #f8fafd;
  --el-table-row-hover-bg-color: #f1f7ff;
  overflow: hidden;
  color: var(--ink-700);
  border-radius: 12px;
}

.el-table::before,
.el-table__inner-wrapper::before {
  display: none;
}

.el-table th.el-table__cell {
  height: 48px;
  color: var(--ink-500) !important;
  font-size: 11px !important;
  font-weight: 800 !important;
  letter-spacing: 0.06em;
  background: #f8fafd !important;
}

.el-table td.el-table__cell {
  height: 52px;
}

.el-tag {
  font-weight: 700;
  border: 0 !important;
  border-radius: 999px !important;
}

.el-tag--info { color: var(--gray-600) !important; background: var(--gray-100) !important; }
.el-tag--success { color: var(--success-text) !important; background: var(--success-bg) !important; }
.el-tag--warning { color: var(--warning-text) !important; background: var(--warning-bg) !important; }
.el-tag--danger { color: var(--danger-text) !important; background: var(--danger-bg) !important; }
.el-tag--primary { color: var(--brand-800) !important; background: var(--brand-50) !important; }

.el-tabs__header {
  margin-bottom: 20px !important;
}

.el-tabs__nav-wrap::after {
  height: 1px !important;
  background: var(--line) !important;
}

.el-tabs__item {
  height: 46px !important;
  color: var(--ink-500) !important;
  font-weight: 700 !important;
}

.el-tabs__item.is-active {
  color: var(--brand-700) !important;
}

.el-tabs__active-bar {
  height: 3px !important;
  background: var(--brand-600) !important;
  border-radius: 3px 3px 0 0;
}

.el-dialog,
.el-message-box {
  border: 1px solid var(--line) !important;
  border-radius: var(--radius-xl) !important;
  box-shadow: var(--shadow-lg) !important;
}

.el-dialog__header {
  padding: 22px 24px 18px !important;
  border-bottom: 1px solid #e7edf5;
}

.el-dialog__body {
  padding: 22px 24px !important;
}

.el-dialog__footer {
  padding: 16px 24px 22px !important;
  border-top: 1px solid #e7edf5;
}

.el-alert {
  border: 1px solid var(--line) !important;
  border-radius: 12px !important;
}

.el-alert--info {
  background: var(--brand-50) !important;
}

.el-upload-dragger {
  background: #f8fafd !important;
  border: 1px dashed #b8c7da !important;
  border-radius: var(--radius-lg) !important;
}

.el-upload-dragger:hover {
  background: var(--brand-50) !important;
  border-color: var(--brand-500) !important;
}

.el-pagination .el-pager li.is-active {
  color: #fff !important;
  background: var(--brand-600) !important;
  border-radius: 7px;
}

.el-progress-bar__outer {
  background: #e9eff6 !important;
  border-radius: 999px !important;
}

.el-progress-bar__inner,
.el-switch.is-checked .el-switch__core,
.el-radio__input.is-checked .el-radio__inner,
.el-checkbox__input.is-checked .el-checkbox__inner {
  background: var(--brand-600) !important;
  border-color: var(--brand-600) !important;
}

.el-descriptions {
  --el-descriptions-item-bordered-label-background: #f8fafd;
}

.el-empty__description p {
  color: var(--ink-500);
}

.el-loading-mask {
  background: rgba(244, 247, 251, 0.84) !important;
  backdrop-filter: blur(3px);
}

::-webkit-scrollbar {
  width: 7px;
  height: 7px;
}

::-webkit-scrollbar-track {
  background: transparent;
}

::-webkit-scrollbar-thumb {
  background: #c5d1df;
  border-radius: 999px;
}

</style>
