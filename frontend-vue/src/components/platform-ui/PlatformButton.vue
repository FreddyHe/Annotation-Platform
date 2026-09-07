<script setup>
defineProps({
  variant: { type: String, default: 'primary' },
  type: { type: String, default: 'button' },
  disabled: { type: Boolean, default: false }
})

defineEmits(['click'])
</script>

<template>
  <button :class="variant" :type="type" :disabled="disabled" @click="$emit('click', $event)">
    <slot />
  </button>
</template>

<style scoped>
button {
  min-height: 42px;
  padding: 0 17px;
  border-radius: 10px;
  font-weight: 750;
  cursor: pointer;
}

button.primary {
  color: #fff;
  background: var(--brand-600);
  border: 1px solid var(--brand-600);
  box-shadow: 0 8px 18px rgba(23, 107, 255, 0.2);
}

button.secondary {
  color: var(--brand-700);
  background: #fff;
  border: 1px solid #cbd9ea;
}

button.danger {
  color: #a23647;
  background: #fff0f2;
  border: 1px solid #ffd5dc;
}

button:disabled { opacity: 0.55; cursor: not-allowed; }
</style>
