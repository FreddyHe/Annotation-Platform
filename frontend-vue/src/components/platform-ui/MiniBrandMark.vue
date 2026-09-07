<template><span class="mini-mark" aria-hidden="true"><i /><i /><i /></span></template>

<style scoped>
.mini-mark {
  position: relative;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  background: var(--brand-900);
  border-radius: 11px;
}

.mini-mark i {
  position: absolute;
  width: 15px;
  height: 2px;
  background: #fff;
  border-radius: 2px;
  transform: rotate(-35deg);
}

.mini-mark i:nth-child(1) { top: 10px; left: 7px; }
.mini-mark i:nth-child(2) { top: 16px; left: 10px; background: #4ca2ff; }
.mini-mark i:nth-child(3) { top: 22px; left: 14px; width: 10px; }
</style>
