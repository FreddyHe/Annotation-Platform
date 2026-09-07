<script setup>
defineProps({
  eyebrow: { type: String, required: true },
  title: { type: String, required: true },
  description: { type: String, default: '' }
})
</script>

<template>
  <header class="page-heading">
    <div>
      <span>{{ eyebrow }}</span>
      <h2>{{ title }}</h2>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.default" class="heading-actions"><slot /></div>
  </header>
</template>

<style scoped>
.page-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 22px;
}

.page-heading > div:first-child > span {
  color: var(--brand-600);
  font-size: 9px;
  font-weight: 900;
  letter-spacing: 0.16em;
}

.page-heading h2 {
  margin: 7px 0 4px;
  color: var(--ink-950);
  font-size: 27px;
  letter-spacing: -0.04em;
}

.page-heading p {
  max-width: 720px;
  margin: 0;
  color: var(--ink-500);
  font-size: 11px;
}

.heading-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

@media (max-width: 760px) {
  .page-heading { align-items: flex-start; flex-direction: column; }
  .heading-actions { width: 100%; }
}
</style>
