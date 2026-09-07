<script setup>
defineProps({ tone: { type: String, default: 'neutral' } })
</script>

<template><span class="status-pill" :class="tone"><i /><slot /></span></template>

<style scoped>
.status-pill {
  display: inline-flex;
  flex: 0 0 auto;
  gap: 5px;
  align-items: center;
  padding: 4px 7px;
  border-radius: 12px;
  font-size: 9px;
  font-weight: 800;
}

.status-pill i { width: 5px; height: 5px; border-radius: 50%; }
.status-pill.success { color: #117354; background: #e8f8f2; }
.status-pill.success i { background: #18a873; box-shadow: 0 0 0 3px rgba(24,168,115,.1); }
.status-pill.neutral { color: #667a93; background: #eef2f6; }
.status-pill.neutral i { background: #8293a8; }
.status-pill.active { color: #176bff; background: #edf5ff; }
.status-pill.active i { background: #176bff; }
.status-pill.warning { color: #9b6412; background: #fff6e7; }
.status-pill.warning i { background: #d98b18; }
.status-pill.danger { color: #a23647; background: #fff0f2; }
.status-pill.danger i { background: var(--danger); }
</style>
