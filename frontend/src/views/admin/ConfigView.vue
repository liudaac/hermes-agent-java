<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Settings, Save, RefreshCw } from 'lucide-vue-next'
import { api, type ConfigSection } from '../../services/api'

const sections = ref<ConfigSection[]>([])
const config = ref<Record<string, any>>({})
const originalConfig = ref<Record<string, any>>({})
const loading = ref(true)
const saving = ref(false)
const toast = ref<{ type: 'success' | 'error'; message: string } | null>(null)
const hasChanges = ref(false)

async function loadConfig() {
  loading.value = true
  try {
    const [schemaResp, configResp] = await Promise.all([
      api.getConfigSchema(),
      api.getConfig()
    ])
    sections.value = schemaResp.sections
    config.value = { ...configResp }
    originalConfig.value = { ...configResp }
    hasChanges.value = false
  } catch (e) {
    showToast('error', 'Failed to load configuration')
  } finally {
    loading.value = false
  }
}

async function saveConfig() {
  saving.value = true
  try {
    await api.updateConfig(config.value)
    originalConfig.value = { ...config.value }
    hasChanges.value = false
    showToast('success', 'Configuration saved')
  } catch (e) {
    showToast('error', 'Failed to save configuration')
  } finally {
    saving.value = false
  }
}

function updateValue(key: string, value: any) {
  config.value[key] = value
  hasChanges.value = JSON.stringify(config.value) !== JSON.stringify(originalConfig.value)
}

function showToast(type: 'success' | 'error', message: string) {
  toast.value = { type, message }
  setTimeout(() => toast.value = null, 3000)
}

function resetChanges() {
  config.value = { ...originalConfig.value }
  hasChanges.value = false
}

onMounted(loadConfig)
</script>

<template>
  <div class="space-y-6">
    <!-- Toast -->
    <div v-if="toast" :class="[
      'fixed top-4 right-4 px-4 py-3 rounded-lg shadow-lg z-50',
      toast.type === 'success' ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
    ]">
      {{ toast.message }}
    </div>

    <!-- Header -->
    <div class="flex items-center justify-between">
      <h2 class="text-xl font-bold flex items-center gap-2">
        <Settings class="h-5 w-5" />
        Configuration
      </h2>
      <div class="flex items-center gap-2">
        <button @click="loadConfig" :disabled="loading"
                class="flex items-center gap-1 px-3 py-2 border rounded hover:bg-gray-50 disabled:opacity-50">
          <RefreshCw :class="['h-4 w-4', loading && 'animate-spin']" />
          Refresh
        </button>
        <button v-if="hasChanges" @click="resetChanges"
                class="px-3 py-2 border rounded hover:bg-gray-50">
          Reset
        </button>
        <button @click="saveConfig" :disabled="!hasChanges || saving"
                :class="[
                  'flex items-center gap-1 px-4 py-2 rounded disabled:opacity-50',
                  hasChanges ? 'bg-blue-600 text-white hover:bg-blue-700' : 'bg-gray-100 text-gray-500'
                ]">
          <Save class="h-4 w-4" />
          {{ saving ? 'Saving...' : 'Save Changes' }}
        </button>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="h-8 w-8 animate-spin rounded-full border-2 border-blue-600 border-t-transparent" />
    </div>

    <!-- Config Sections -->
    <div v-else class="space-y-6">
      <div v-for="section in sections" :key="section.name" class="bg-white border rounded-lg">
        <div class="px-4 py-3 border-b">
          <h3 class="font-medium">{{ section.title }}</h3>
        </div>
        <div class="p-4 space-y-4">
          <div v-for="field in section.fields" :key="field.key" class="space-y-1">
            <label class="block text-sm font-medium text-gray-700">
              {{ field.label }}
              <span v-if="field.secret" class="ml-1 text-xs text-orange-600">(Secret)</span>
            </label>
            <p v-if="field.description" class="text-xs text-gray-500">{{ field.description }}</p>
            
            <!-- String/Password Input -->
            <input v-if="field.type === 'string' || field.type === 'password'"
                   :type="field.secret ? 'password' : 'text'"
                   :value="config[field.key] ?? field.default ?? ''"
                   @input="updateValue(field.key, ($event.target as HTMLInputElement).value)"
                   class="w-full px-3 py-2 border rounded focus:outline-none focus:ring-2 focus:ring-blue-500" />
            
            <!-- Number Input -->
            <input v-else-if="field.type === 'number'"
                   type="number"
                   :value="config[field.key] ?? field.default ?? 0"
                   @input="updateValue(field.key, Number(($event.target as HTMLInputElement).value))"
                   class="w-full px-3 py-2 border rounded focus:outline-none focus:ring-2 focus:ring-blue-500" />
            
            <!-- Boolean Toggle -->
            <label v-else-if="field.type === 'boolean'" class="flex items-center gap-2 cursor-pointer">
              <input type="checkbox"
                     :checked="config[field.key] ?? field.default ?? false"
                     @change="updateValue(field.key, ($event.target as HTMLInputElement).checked)"
                     class="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
              <span class="text-sm">Enabled</span>
            </label>
            
            <!-- Select Dropdown -->
            <select v-else-if="field.type === 'select'"
                    :value="config[field.key] ?? field.default ?? ''"
                    @change="updateValue(field.key, ($event.target as HTMLSelectElement).value)"
                    class="w-full px-3 py-2 border rounded focus:outline-none focus:ring-2 focus:ring-blue-500">
              <option v-for="opt in field.options" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </option>
            </select>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
