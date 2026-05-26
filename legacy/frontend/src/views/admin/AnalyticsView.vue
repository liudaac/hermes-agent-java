<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { BarChart3, MessageSquare, Users, Wrench, TrendingUp } from 'lucide-vue-next'
import { api } from '../../services/api'

const analytics = ref<{
  total_sessions: number
  total_messages: number
  active_tenants: number
  tool_calls: Record<string, number>
} | null>(null)
const loading = ref(true)

const topTools = computed(() => {
  if (!analytics.value?.tool_calls) return []
  return Object.entries(analytics.value.tool_calls)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 10)
})

const totalToolCalls = computed(() => {
  if (!analytics.value?.tool_calls) return 0
  return Object.values(analytics.value.tool_calls).reduce((a, b) => a + b, 0)
})

async function loadAnalytics() {
  loading.value = true
  try {
    analytics.value = await api.getAnalytics()
  } catch (e) {
    console.error('Failed to load analytics:', e)
  } finally {
    loading.value = false
  }
}

onMounted(loadAnalytics)
</script>

<template>
  <div class="space-y-6">
    <!-- Header -->
    <div class="flex items-center justify-between">
      <h2 class="text-xl font-bold flex items-center gap-2">
        <BarChart3 class="h-5 w-5" />
        Analytics
      </h2>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="h-8 w-8 animate-spin rounded-full border-2 border-blue-600 border-t-transparent" />
    </div>

    <template v-else-if="analytics">
      <!-- Stats Cards -->
      <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div class="bg-white border rounded-lg p-4">
          <div class="flex items-center justify-between">
            <span class="text-sm text-gray-600">Total Sessions</span>
            <MessageSquare class="h-4 w-4 text-blue-500" />
          </div>
          <div class="text-3xl font-bold mt-2">{{ analytics.total_sessions.toLocaleString() }}</div>
        </div>
        <div class="bg-white border rounded-lg p-4">
          <div class="flex items-center justify-between">
            <span class="text-sm text-gray-600">Total Messages</span>
            <TrendingUp class="h-4 w-4 text-green-500" />
          </div>
          <div class="text-3xl font-bold mt-2">{{ analytics.total_messages.toLocaleString() }}</div>
        </div>
        <div class="bg-white border rounded-lg p-4">
          <div class="flex items-center justify-between">
            <span class="text-sm text-gray-600">Active Tenants</span>
            <Users class="h-4 w-4 text-purple-500" />
          </div>
          <div class="text-3xl font-bold mt-2">{{ analytics.active_tenants }}</div>
        </div>
      </div>

      <!-- Tool Usage -->
      <div class="bg-white border rounded-lg">
        <div class="px-4 py-3 border-b">
          <h3 class="font-medium flex items-center gap-2">
            <Wrench class="h-4 w-4" />
            Tool Usage ({{ totalToolCalls.toLocaleString() }} total calls)
          </h3>
        </div>
        <div class="divide-y">
          <div v-for="[tool, count] in topTools" :key="tool" class="px-4 py-3">
            <div class="flex items-center justify-between mb-2">
              <span class="font-medium">{{ tool }}</span>
              <span class="text-sm text-gray-500">{{ count.toLocaleString() }} calls</span>
            </div>
            <div class="w-full bg-gray-200 rounded-full h-2">
              <div class="bg-blue-600 h-2 rounded-full transition-all"
                   :style="{ width: `${(count / Math.max(...Object.values(analytics.tool_calls))) * 100}%` }"></div>
            </div>
          </div>
        </div>
        <div v-if="topTools.length === 0" class="px-4 py-8 text-center text-gray-500">
          No tool usage data available
        </div>
      </div>
    </template>
  </div>
</template>
