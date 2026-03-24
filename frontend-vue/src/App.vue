<template>
  <router-view />
</template>

<script setup>
</script>

<style>
@import url('https://fonts.googleapis.com/css2?family=DM+Sans:ital,opsz,wght@0,9..40,300;0,9..40,400;0,9..40,500;0,9..40,600;1,9..40,400&display=swap');

:root {
  /* Brand palette */
  --brand-50: #EEEDFE;
  --brand-100: #CECBF6;
  --brand-200: #AFA9EC;
  --brand-400: #7F77DD;
  --brand-600: #534AB7;
  --brand-800: #3C3489;
  --brand-900: #26215C;

  /* Neutral palette */
  --gray-50: #fafaf9;
  --gray-100: #f5f5f4;
  --gray-200: #e7e5e4;
  --gray-300: #d6d3d1;
  --gray-400: #a8a29e;
  --gray-500: #78716c;
  --gray-600: #57534e;
  --gray-700: #44403c;
  --gray-800: #292524;
  --gray-900: #1c1917;

  /* Semantic */
  --success-bg: #E1F5EE;
  --success-text: #085041;
  --success-border: #5DCAA5;
  --warning-bg: #FAEEDA;
  --warning-text: #854F0B;
  --warning-border: #EF9F27;
  --danger-bg: #FCEBEB;
  --danger-text: #791F1F;
  --danger-border: #F09595;
  --info-bg: #E6F1FB;
  --info-text: #0C447C;
  --info-border: #85B7EB;

  /* Layout */
  --sidebar-width: 232px;
  --header-height: 56px;
  --radius-sm: 6px;
  --radius-md: 8px;
  --radius-lg: 12px;
  --radius-xl: 16px;

  /* Transitions */
  --ease-out: cubic-bezier(0.16, 1, 0.3, 1);
}

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: 'DM Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  background-color: var(--gray-100);
  color: var(--gray-900);
  font-size: 14px;
  line-height: 1.6;
}

#app {
  font-family: inherit;
}

/* ===== Element Plus Global Overrides ===== */

/* Primary color */
:root {
  --el-color-primary: #534AB7;
  --el-color-primary-light-3: #7F77DD;
  --el-color-primary-light-5: #AFA9EC;
  --el-color-primary-light-7: #CECBF6;
  --el-color-primary-light-8: #EEEDFE;
  --el-color-primary-light-9: #F6F5FE;
  --el-color-primary-dark-2: #3C3489;

  --el-border-radius-base: var(--radius-md);
  --el-border-radius-small: var(--radius-sm);
  --el-border-radius-round: 20px;
  --el-border-color: var(--gray-200);
  --el-border-color-light: var(--gray-200);
  --el-border-color-lighter: var(--gray-100);
  --el-bg-color: #fff;
  --el-bg-color-page: var(--gray-100);
  --el-text-color-primary: var(--gray-900);
  --el-text-color-regular: var(--gray-700);
  --el-text-color-secondary: var(--gray-500);
  --el-text-color-placeholder: var(--gray-400);
  --el-font-family: 'DM Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  --el-font-size-base: 14px;

  --el-fill-color-light: var(--gray-50);
  --el-fill-color-lighter: var(--gray-100);
  --el-fill-color-blank: #fff;
}

/* Cards */
.el-card {
  border: 0.5px solid var(--gray-200) !important;
  border-radius: var(--radius-lg) !important;
  box-shadow: none !important;
  transition: border-color 0.2s var(--ease-out);
}

.el-card:hover {
  border-color: var(--gray-300) !important;
}

.el-card__header {
  border-bottom: 0.5px solid var(--gray-200) !important;
  padding: 16px 20px !important;
  font-weight: 500;
}

.el-card__body {
  padding: 20px !important;
}

/* Buttons */
.el-button--primary {
  background-color: var(--brand-600) !important;
  border-color: var(--brand-600) !important;
  border-radius: var(--radius-md) !important;
  font-weight: 500;
  letter-spacing: -0.01em;
  transition: all 0.15s var(--ease-out);
}

.el-button--primary:hover,
.el-button--primary:focus {
  background-color: var(--brand-800) !important;
  border-color: var(--brand-800) !important;
}

.el-button--default {
  border-radius: var(--radius-md) !important;
  border-color: var(--gray-200) !important;
  color: var(--gray-700) !important;
  font-weight: 500;
}

.el-button--default:hover {
  color: var(--brand-600) !important;
  border-color: var(--brand-200) !important;
  background-color: var(--brand-50) !important;
}

/* Tags */
.el-tag {
  border-radius: 20px !important;
  border: none !important;
  font-weight: 500;
  letter-spacing: -0.01em;
}

.el-tag--info {
  background-color: var(--gray-100) !important;
  color: var(--gray-600) !important;
}

.el-tag--success {
  background-color: var(--success-bg) !important;
  color: var(--success-text) !important;
}

.el-tag--warning {
  background-color: var(--warning-bg) !important;
  color: var(--warning-text) !important;
}

.el-tag--danger {
  background-color: var(--danger-bg) !important;
  color: var(--danger-text) !important;
}

.el-tag--primary {
  background-color: var(--brand-50) !important;
  color: var(--brand-800) !important;
}

/* Tables */
.el-table {
  --el-table-border-color: var(--gray-200);
  --el-table-header-bg-color: var(--gray-50);
  --el-table-row-hover-bg-color: var(--brand-50);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.el-table th.el-table__cell {
  font-weight: 500 !important;
  color: var(--gray-600) !important;
  font-size: 12px !important;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

/* Tabs */
.el-tabs__item {
  font-weight: 500 !important;
  color: var(--gray-500) !important;
  transition: color 0.15s;
}

.el-tabs__item.is-active {
  color: var(--brand-600) !important;
}

.el-tabs__active-bar {
  background-color: var(--brand-600) !important;
}

/* Dialog */
.el-dialog {
  border-radius: var(--radius-xl) !important;
  overflow: hidden;
}

.el-dialog__header {
  padding: 20px 24px 16px !important;
  border-bottom: 0.5px solid var(--gray-200);
}

.el-dialog__body {
  padding: 20px 24px !important;
}

.el-dialog__footer {
  padding: 16px 24px 20px !important;
  border-top: 0.5px solid var(--gray-200);
}

/* Form */
.el-input__wrapper {
  border-radius: var(--radius-md) !important;
  box-shadow: 0 0 0 0.5px var(--gray-200) !important;
  transition: box-shadow 0.15s var(--ease-out);
}

.el-input__wrapper:hover {
  box-shadow: 0 0 0 0.5px var(--gray-300) !important;
}

.el-input__wrapper.is-focus {
  box-shadow: 0 0 0 1.5px var(--brand-400) !important;
}

.el-textarea__inner {
  border-radius: var(--radius-md) !important;
}

/* Select */
.el-select__wrapper {
  border-radius: var(--radius-md) !important;
}

/* Pagination */
.el-pagination .el-pager li.is-active {
  background-color: var(--brand-600) !important;
  color: #fff !important;
  border-radius: var(--radius-sm);
}

/* Progress */
.el-progress-bar__outer {
  border-radius: 20px !important;
  background-color: var(--gray-100) !important;
}

.el-progress-bar__inner {
  border-radius: 20px !important;
}

/* Alert */
.el-alert {
  border-radius: var(--radius-lg) !important;
  border: 0.5px solid var(--gray-200) !important;
}

.el-alert--info {
  background-color: var(--gray-50) !important;
}

/* Steps */
.el-step__head.is-finish {
  color: var(--brand-600) !important;
  border-color: var(--brand-600) !important;
}

.el-step__title.is-finish {
  color: var(--brand-600) !important;
}

/* Message Box */
.el-message-box {
  border-radius: var(--radius-xl) !important;
}

/* Statistic */
.el-statistic__head {
  font-size: 12px !important;
  color: var(--gray-500) !important;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.el-statistic__content {
  font-weight: 600 !important;
  color: var(--gray-900) !important;
}

/* Upload */
.el-upload-dragger {
  border-radius: var(--radius-lg) !important;
  border: 1.5px dashed var(--gray-300) !important;
  transition: border-color 0.2s;
}

.el-upload-dragger:hover {
  border-color: var(--brand-400) !important;
}

/* Switch */
.el-switch.is-checked .el-switch__core {
  background-color: var(--brand-600) !important;
  border-color: var(--brand-600) !important;
}

/* Radio */
.el-radio__input.is-checked .el-radio__inner {
  background-color: var(--brand-600) !important;
  border-color: var(--brand-600) !important;
}

.el-radio__input.is-checked + .el-radio__label {
  color: var(--brand-600) !important;
}

/* Checkbox */
.el-checkbox__input.is-checked .el-checkbox__inner {
  background-color: var(--brand-600) !important;
  border-color: var(--brand-600) !important;
}

/* Descriptions */
.el-descriptions {
  --el-descriptions-item-bordered-label-background: var(--gray-50);
}

/* Timeline */
.el-timeline-item__node--normal {
  background-color: var(--brand-600);
}

/* Empty */
.el-empty__description p {
  color: var(--gray-400);
}

/* Scrollbar */
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-track {
  background: transparent;
}

::-webkit-scrollbar-thumb {
  background: var(--gray-300);
  border-radius: 3px;
}

::-webkit-scrollbar-thumb:hover {
  background: var(--gray-400);
}
</style>
