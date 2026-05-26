<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Activity, Server, Clock, CheckCircle, XCircle } from 'lucide-vue-next'
import { agentApi, type AgentStatus } from '../services/api'

const status = ref<AgentStatus | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
let intervalId: number | null = null

const fetchStatus = async () => {
  try {
    const response = await agentApi.getStatus()
    status.value = response.data
    error.value = null
  } catch (err) {
    error.value = 'Failed to connect to agent'
  } finally {
    loading.value = false
  }
}

const formatUptime = (seconds: number): string => {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (hours > 0) {
    return `${hours}h ${minutes}m`
  }
  return `${minutes}m`
}

onMounted(() => {
  fetchStatus()
  intervalId = window.setInterval(fetchStatus, 5000)
})

onUnmounted(() => {
  if (intervalId) {
    clearInterval(intervalId)
  }
})
</script>

<template>
  <!-- Loading State -->
  <div v-if="loading" class="bg-white rounded-lg shadow-md p-6 animate-pulse">
    <div class="h-4 bg-gray-200 rounded w-1/3 mb-4"></div>
    <div class="h-8 bg-gray-200 rounded w-1/2"></div>
  </div>

  <!-- Loaded State -->
  <div v-else class="bg-white rounded-lg shadow-md p-6">
    <div class="flex items-center justify-between mb-4">
      <h2 class="text-lg font-semibold text-gray-900 flex items-center gap-2">
        <Activity class="w-5 h-5 text-blue-500" />
        Agent Status
      </h2>
      <span v-if="status?.connected" class="flex items-center gap-1 text-green-600 text-sm font-medium">
        <CheckCircle class="w-4 h-4" />
        Online
      </span>
      <span v-else class="flex items-center gap-1 text-red-600 text-sm font-medium">
        <XCircle class="w-4 h-4" />
        Offline
      </span>
    </div>

    <div v-if="error" class="text-red-500 text-sm">{{ error }}</div>
    
    <template v-else>
      <div class="grid grid-cols-2 gap-4">
        <div class="flex items-center gap-3">
          <Server class="w-5 h-5 text-gray-400" />
          <div>
            <p class="text-xs text-gray-500">Version</p>
            <p class="text-sm font-medium text-gray-900">{{ status?.version || 'Unknown' }}</p>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <Clock class="w-5 h-5 text-gray-400" />
          <div>
            <p class="text-xs text-gray-500">Uptime</p>
            <p class="text-sm font-medium text-gray-900">
              {{ status?.uptime ? formatUptime(status.uptime) : 'N/A' }}
            </p>
          </div>
        </div>
      </div>
      
      <div class="mt-4 pt-4 border-t border-gray-100">
        <p class="text-xs text-gray-500 mb-2">Active Adapters</p>
        <div class="flex flex-wrap gap-2">
          <template v-if="status?.adapters?.length">
            <span
              v-for="adapter in status.adapters"
              :key="adapter"
              class="px-2 py-1 bg-blue-50 text-blue-700 text-xs rounded-full"
            >
              {{ adapter }}
            </span>
          </template>
          <span v-else class="text-xs text-gray-400">No adapters</span>
        </div>
      </div>
    </template>
  </div>
</template>
