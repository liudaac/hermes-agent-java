<script setup lang="ts">
import ChatPanel from '../components/ChatPanel.vue'
import StatusCard from '../components/StatusCard.vue'
import ToolsPanel from '../components/ToolsPanel.vue'
</script>

<template>
  <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
    <!-- Left Column - Status & Tools -->
    <div class="space-y-6">
      <StatusCard />
      <ToolsPanel />
    </div>

    <!-- Right Column - Chat -->
    <div class="lg:col-span-2">
      <ChatPanel />
    </div>
  </div>
</template>
