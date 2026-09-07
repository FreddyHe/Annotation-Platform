<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], required: true },
  hint: { type: String, default: '' },
  tone: { type: String, default: 'total' },
  mark: { type: String, default: '' }
})
</script>

<template>
  <article :class="{ warning: tone === 'danger' }">
    <span>{{ label }}</span>
    <strong>{{ value }}</strong>
    <small>{{ hint }}</small>
    <i class="metric-mark" :class="tone">{{ mark }}</i>
  </article>
</template>

<style scoped>
article {
  position: relative;
  display: grid;
  min-height: 119px;
  padding: 18px;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 15px;
  box-shadow: var(--shadow-sm);
}

article::before {
  position: absolute;
  top: 0;
  left: 18px;
  width: 34px;
  height: 3px;
  content: '';
  background: var(--brand-600);
  border-radius: 0 0 3px 3px;
}

article.warning::before { background: var(--danger); }
article > span { color: var(--ink-500); font-size: 10px; font-weight: 700; }
article > strong { margin: 6px 0 4px; color: var(--ink-950); font-size: 27px; }
article > small { color: var(--ink-500); font-size: 9px; }

.metric-mark {
  position: absolute;
  right: 15px;
  bottom: 16px;
  display: grid;
  width: 37px;
  height: 37px;
  color: #3c7dd2;
  background: #edf5ff;
  border-radius: 11px;
  font-size: 12px;
  font-style: normal;
  font-weight: 900;
  place-items: center;
}

.metric-mark.running { color: #14875f; background: #eaf8f3; }
.metric-mark.warning { color: #a0640b; background: #fff6e7; }
.metric-mark.danger { color: #c04455; background: #fff0f2; }
</style>
