<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Wrench, Terminal, Globe, FileText, Search, Image, Code, Database } from 'lucide-vue-next'
import { agentApi, type ToolInfo } from '../services/api'

const toolIcons: Record<string, string> = {
  terminal: Terminal,
  web_search: Globe,
  file_operations: FileText,
  browser: Search,
  image_generation: Image,
  code_execution: Code,
  memory: Database,
}

const tools = ref<ToolInfo[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

const fetchTools = async () => {
  try {
    const response = await agentApi.getTools()
    tools.value = response.data
    error.value = null
  } catch (err) {
    error.value = 'Failed to load tools'
    // Fallback tools for demo
    tools.value = [
      { name: 'terminal', description: 'Execute terminal commands', emoji: '💻' },
      { name: 'web_search', description: 'Search the web', emoji: '🔍' },
      { name: 'file_operations', description: 'Read and write files', emoji: '📁' },
      { name: 'browser', description: 'Browse websites', emoji: '🌐' },
      { name: 'image_generation', description: 'Generate images', emoji: '🎨' },
      { name: 'code_execution', description: 'Execute code', emoji: '⚡' },
    ]
  } finally {
    loading.value = false
  }
}

onMounted(fetchTools)
</script>

<template>
  <!-- Loading State -->
  <div v-if="loading" class="bg-white rounded-lg shadow-md p-6">
    <div class="flex items-center gap-2 mb-4">
      <Wrench class="w-5 h-5 text-blue-500" />
      <h2 class="text-lg font-semibold text-gray-900">Available Tools</h2>
    </div>
    <div class="space-y-3">
      <div v-for="i in 3" :key="i" class="animate-pulse flex items-center gap-3 p-3 rounded-lg bg-gray-50">
        <div class="w-10 h-10 bg-gray-200 rounded-lg"></div>
        <div class="flex-1">
          <div class="h-4 bg-gray-200 rounded w-24 mb-2"></div>
          <div class="h-3 bg-gray-200 rounded w-32"></div>
        </div>
      </div>
    </div>
  </div>

  <!-- Loaded State -->
  <div v-else class="bg-white rounded-lg shadow-md p-6">
    <div class="flex items-center justify-between mb-4">
      <h2 class="text-lg font-semibold text-gray-900 flex items-center gap-2">
        <Wrench class="w-5 h-5 text-blue-500" />
        Available Tools
      </h2>
      <span class="text-sm text-gray-500">{{ tools.length }} tools</span>
    </div>

    <div v-if="error" class="text-sm text-amber-600 mb-4 bg-amber-50 p-2 rounded">
      {{ error }}
    </div>

    <div class="space-y-2">
      <div
        v-for="tool in tools"
        :key="tool.name"
        class="flex items-center gap-3 p-3 rounded-lg bg-gray-50 hover:bg-gray-100 transition-colors cursor-pointer group"
      >
        <div class="w-10 h-10 rounded-lg bg-white border border-gray-200 flex items-center justify-center text-gray-600 group-hover:border-blue-300 group-hover:text-blue-500 transition-colors">
          <component :is="toolIcons[tool.name]" v-if="toolIcons[tool.name]" class="w-5 h-5" />
          <span v-else class="text-lg">{{ tool.emoji }}</span>
        </div>
        <div class="flex-1 min-w-0">
          <h3 class="text-sm font-medium text-gray-900 capitalize">
            {{ tool.name.replace('_', ' ') }}
          </h3>
          <p class="text-xs text-gray-500 truncate">{{ tool.description }}</p>
        </div>
      </div>
    </div>

    <div class="mt-4 pt-4 border-t border-gray-200">
      <p class="text-xs text-gray-500">
        Hermes can use these tools to help you. Just ask in natural language.
      </p>
    </div>
  </div>
</template>
