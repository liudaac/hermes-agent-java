<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { FileText, Download, RefreshCw, Terminal } from 'lucide-vue-next'
import { api } from '../../services/api'

const logs = ref<string[]>([])
const loading = ref(true)
const service = ref('')
const lines = ref(100)
const autoScroll = ref(true)
const logContainer = ref<HTMLDivElement>()

const services = [
  { value: '', label: 'All Services' },
  { value: 'gateway', label: 'Gateway' },
  { value: 'agent', label: 'Agent' },
  { value: 'cron', label: 'Cron' }
]

async function loadLogs() {
  loading.value = true
  try {
    logs.value = await api.getLogs(service.value || undefined, lines.value)
    if (autoScroll.value) {
      setTimeout(() => {
        logContainer.value?.scrollTo(0, logContainer.value.scrollHeight)
      }, 100)
    }
  } catch (e) {
    logs.value = ['Failed to load logs']
  } finally {
    loading.value = false
  }
}

function downloadLogs() {
  const content = logs.value.join('\n')
  const blob = new Blob([content], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `hermes-logs-${new Date().toISOString().split('T')[0]}.txt`
  a.click()
  URL.revokeObjectURL(url)
}

function formatLogLine(line: string): string {
  // Highlight timestamps, log levels, etc.
  return line
    .replace(/\[ERROR\]/g, '<span class="text-red-400">[ERROR]</span>')
    .replace(/\[WARN\]/g, '<span class="text-yellow-400">[WARN]</span>')
    .replace(/\[INFO\]/g, '<span class="text-blue-400">[INFO]</span>')
    .replace(/\[DEBUG\]/g, '<span class="text-gray-400">[DEBUG]</span>')
}

onMounted(loadLogs)
</script>

<template>
  <div class="space-y-4 h-full flex flex-col">
    <!-- Header -->
    <div class="flex flex-wrap items-center justify-between gap-2">
      <h2 class="text-xl font-bold flex items-center gap-2">
        <FileText class="h-5 w-5" />
        Logs
      </h2>
      <div class="flex items-center gap-2">
        <select v-model="service" @change="loadLogs"
                class="px-3 py-2 border rounded focus:outline-none focus:ring-2 focus:ring-blue-500">
          <option v-for="s in services" :key="s.value" :value="s.value">{{ s.label }}</option>
        </select>
        <select v-model="lines" @change="loadLogs"
                class="px-3 py-2 border rounded focus:outline-none focus:ring-2 focus:ring-blue-500">
          <option :value="50">50 lines</option>
          <option :value="100">100 lines</option>
          <option :value="500">500 lines</option>
          <option :value="1000">1000 lines</option>
        </select>
        <button @click="loadLogs" :disabled="loading"
                class="flex items-center gap-1 px-3 py-2 border rounded hover:bg-gray-50 disabled:opacity-50">
          <RefreshCw :class="['h-4 w-4', loading && 'animate-spin']" />
        </button>
        <button @click="downloadLogs"
                class="flex items-center gap-1 px-3 py-2 border rounded hover:bg-gray-50">
          <Download class="h-4 w-4" />
        </button>
      </div>
    </div>

    <!-- Log View -->
    <div ref="logContainer" class="flex-1 bg-gray-900 rounded-lg overflow-auto font-mono text-sm">
      <div v-if="loading" class="flex items-center justify-center py-12">
        <div class="h-8 w-8 animate-spin rounded-full border-2 border-white border-t-transparent" />
      </div>
      <div v-else class="p-4 space-y-1">
        <div v-for="(line, i) in logs" :key="i" 
             class="text-gray-300 hover:bg-gray-800 px-2 -mx-2"
             v-html="formatLogLine(line)"></div>
        <div v-if="logs.length === 0" class="text-gray-500 text-center py-8">
          No logs available
        </div>
      </div>
    </div>

    <!-- Footer -->
    <div class="flex items-center justify-between text-sm text-gray-500">
      <label class="flex items-center gap-2 cursor-pointer">
        <input type="checkbox" v-model="autoScroll" class="rounded" />
        Auto-scroll
      </label>
      <span>{{ logs.length }} lines</span>
    </div>
  </div>
</template>
