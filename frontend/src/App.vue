<script setup lang="ts">
import { ref } from 'vue'
import Layout from './components/Layout.vue'
import StatusCard from './components/StatusCard.vue'
import ChatPanel from './components/ChatPanel.vue'
import ToolsPanel from './components/ToolsPanel.vue'
import TenantPanel from './components/TenantPanel.vue'

type ViewType = 'chat' | 'tenants'
const activeView = ref<ViewType>('chat')
</script>

<template>
  <Layout>
    <!-- Navigation -->
    <div class="mb-6 border-b">
      <div class="flex gap-4">
        <button
          @click="activeView = 'chat'"
          :class="[
            'px-4 py-2 transition-colors',
            activeView === 'chat' 
              ? 'border-b-2 border-blue-600 text-blue-600' 
              : 'text-gray-600 hover:text-gray-800'
          ]"
        >
          Chat & Tools
        </button>
        <button
          @click="activeView = 'tenants'"
          :class="[
            'px-4 py-2 transition-colors',
            activeView === 'tenants' 
              ? 'border-b-2 border-blue-600 text-blue-600' 
              : 'text-gray-600 hover:text-gray-800'
          ]"
        >
          Tenant Management
        </button>
      </div>
    </div>

    <!-- Chat View -->
    <div v-if="activeView === 'chat'" class="grid grid-cols-1 lg:grid-cols-3 gap-6">
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

    <!-- Tenant View -->
    <div v-else class="max-w-4xl mx-auto">
      <TenantPanel />
    </div>
  </Layout>
</template>
