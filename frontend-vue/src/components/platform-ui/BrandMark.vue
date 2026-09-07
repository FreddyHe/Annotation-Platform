<template>
  <span class="brand-mark" aria-hidden="true"><i /><i /><i /></span>
</template>

<style scoped>
.brand-mark {
  position: relative;
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  overflow: hidden;
  background: var(--brand-900);
  border-radius: 12px;
  box-shadow: 0 9px 22px rgba(8, 43, 92, 0.24);
  place-content: center;
}

.brand-mark i {
  position: absolute;
  width: 17px;
  height: 3px;
  background: #fff;
  border-radius: 3px;
  transform: rotate(-35deg);
}

.brand-mark i:nth-child(1) { top: 11px; left: 8px; }
.brand-mark i:nth-child(2) { top: 18px; left: 12px; background: #4ca2ff; }
.brand-mark i:nth-child(3) { top: 25px; left: 15px; width: 12px; }
</style>
