<template>
  <div class="metric-card">
    <span>{{ label }}</span>
    <strong>{{ value }}</strong>
  </div>
</template>

<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], required: true }
});
</script>
