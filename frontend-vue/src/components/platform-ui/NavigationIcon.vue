<script setup lang="ts">
defineProps<{ name: string }>()
</script>

<template>
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <template v-if="name === 'overview'">
      <rect class="icon-fill" x="4" y="4" width="6" height="8" rx="1" />
      <rect class="icon-fill" x="14" y="4" width="6" height="4" rx="1" />
      <rect class="icon-fill" x="4" y="16" width="6" height="4" rx="1" />
      <rect class="icon-fill" x="14" y="12" width="6" height="8" rx="1" />
    </template>
    <template v-else-if="name === 'insight'">
      <path d="M5 19V9M12 19V5M19 19v-7M3 19h18" />
    </template>
    <template v-else-if="name === 'map'">
      <path d="m4 6 5-2 6 2 5-2v14l-5 2-6-2-5 2V6ZM9 4v14M15 6v14" />
      <circle cx="15" cy="11" r="2.2" />
    </template>
    <template v-else-if="name === 'camera'">
      <rect x="4" y="5" width="13" height="14" rx="3" />
      <path d="m17 10 3-2v8l-3-2v-4Z" />
    </template>
    <template v-else-if="name === 'task'">
      <rect x="5" y="4" width="14" height="16" rx="2" />
      <path d="M9 9h6M9 13h4M9 17h6" />
    </template>
    <template v-else-if="name === 'model'">
      <path d="M7 5h10l3 7-3 7H7l-3-7 3-7Z" />
      <circle cx="12" cy="12" r="3" />
    </template>
    <template v-else-if="name === 'alarm'">
      <path d="M6 9a6 6 0 0 1 12 0c0 6 2 6 2 8H4c0-2 2-2 2-8ZM10 20h4" />
    </template>
    <template v-else-if="name === 'notification'">
      <path d="M5 8.5A7 7 0 0 1 19 9v5l2 3H3l2-3V8.5ZM9.5 20h5" />
      <path d="m9 11 2 2 4-4" />
    </template>
    <template v-else-if="name === 'integration'">
      <circle cx="6" cy="12" r="2.5" />
      <circle cx="18" cy="7" r="2.5" />
      <circle cx="18" cy="17" r="2.5" />
      <path d="m8.5 11 7-3M8.5 13l7 3" />
    </template>
    <template v-else-if="name === 'identity'">
      <circle cx="9" cy="8" r="3" />
      <path d="M3.5 19c.6-3.4 2.4-5 5.5-5s4.9 1.6 5.5 5M16 11h5M18.5 8.5v5" />
    </template>
    <template v-else-if="name === 'audit'">
      <rect x="6" y="3" width="12" height="18" rx="2" />
      <path d="M9 8h6M9 12h6M9 16h4" />
    </template>
    <template v-else-if="name === 'governance'">
      <path d="M12 3 4 7v5c0 5 3.4 8 8 9 4.6-1 8-4 8-9V7l-8-4Z" />
      <path d="m9 12 2 2 4-5" />
    </template>
    <template v-else-if="name === 'video-group'">
      <rect x="3.5" y="5" width="17" height="14" rx="3" />
      <path d="m10 9 5 3-5 3V9Z" />
    </template>
    <template v-else-if="name === 'intelligence-group'">
      <path d="M8 4h8l4 7-4 7H8l-4-7 4-7Z" />
      <path d="M9 20h6M12 18v2M9 11h6M12 8v6" />
    </template>
    <template v-else-if="name === 'response-group'">
      <path d="M12 4a7 7 0 0 1 7 7v4l2 3H3l2-3v-4a7 7 0 0 1 7-7Z" />
      <path d="M9 21h6M12 1v2" />
    </template>
    <template v-else-if="name === 'administration-group'">
      <path d="M4 7h10M18 7h2M4 17h2M10 17h10M14 4v6M6 14v6" />
    </template>
    <template v-else-if="name === 'resources'">
      <path d="m12 3 9 5-9 5-9-5 9-5Z" />
      <path d="m3 12 9 5 9-5M3 16l9 5 9-5" />
    </template>
    <template v-else>
      <path d="M3 12h4l2-5 4 10 2-5h6" />
    </template>
  </svg>
</template>
