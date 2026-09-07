<script setup>
defineProps({
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  padded: { type: Boolean, default: true }
})
</script>

<template>
  <section class="management-card">
    <header v-if="title || $slots.actions" class="toolbar">
      <div><h3>{{ title }}</h3><p v-if="description">{{ description }}</p></div>
      <div v-if="$slots.actions" class="actions"><slot name="actions" /></div>
    </header>
    <div class="panel-body" :class="{ padded }"><slot /></div>
  </section>
</template>

<style scoped>
.management-card {
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 16px;
  box-shadow: var(--shadow-sm);
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 78px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.toolbar h3 { margin: 0 0 3px; font-size: 15px; }
.toolbar p { margin: 0; color: var(--ink-500); font-size: 10px; }
.actions { display: flex; gap: 8px; align-items: center; }
.panel-body.padded { padding: 18px; }

@media (max-width: 760px) {
  .toolbar { align-items: flex-start; flex-direction: column; gap: 12px; }
  .actions { width: 100%; }
}
</style>
