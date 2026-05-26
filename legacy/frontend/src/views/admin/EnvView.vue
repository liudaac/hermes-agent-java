<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { KeyRound, Plus, Trash2, Eye, EyeOff, Save } from 'lucide-vue-next'
import { api } from '../../services/api'

const envVars = ref<Record<string, string>>({})
const loading = ref(true)
const editingKey = ref('')
const editingValue = ref('')
const showSecrets = ref<Record<string, boolean>>({})
const toast = ref<{ type: 'success' | 'error'; message: string } | null>(null)

// Common API key names that should be hidden
const secretKeys = ['api_key', 'apikey', 'secret', 'password', 'token', 'private_key']

async function loadEnvVars() {
  loading.value = true
  try {
    envVars.value = await api.getEnvVars()
  } catch (e) {
    showToast('error', 'Failed to load environment variables')
  } finally {
    loading.value = false
  }
}

async function saveEnvVar() {
  if (!editingKey.value.trim()) return
  try {
    await api.setEnvVar(editingKey.value.trim(), editingValue.value)
    envVars.value[editingKey.value.trim()] = editingValue.value
    editingKey.value = ''
    editingValue.value = ''
    showToast('success', 'Environment variable saved')
  } catch (e) {
    showToast('error', 'Failed to save environment variable')
  }
}

async function deleteEnvVar(key: string) {
  if (!confirm(`Delete "${key}"?`)) return
  try {
    await api.deleteEnvVar(key)
    delete envVars.value[key]
    showToast('success', 'Environment variable deleted')
  } catch (e) {
    showToast('error', 'Failed to delete environment variable')
  }
}

function isSecretKey(key: string): boolean {
  const lowerKey = key.toLowerCase()
  return secretKeys.some(sk => lowerKey.includes(sk))
}

function toggleSecret(key: string) {
  showSecrets.value[key] = !showSecrets.value[key]
}

function showToast(type: 'success' | 'error', message: string) {
  toast.value = { type, message }
  setTimeout(() => toast.value = null, 3000)
}

onMounted(loadEnvVars)
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
        <KeyRound class="h-5 w-5" />
        API Keys & Environment
      </h2>
    </div>

    <!-- Add New -->
    <div class="bg-white border rounded-lg p-4">
      <h3 class="font-medium mb-4">Add New Variable</h3>
      <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
        <input v-model="editingKey" type="text" placeholder="Key (e.g., OPENAI_API_KEY)"
               class="px-3 py-2 border rounded focus:outline-none focus:ring-2 focus:ring-blue-500" />
        <input v-model="editingValue" type="password" placeholder="Value"
               class="px-3 py-2 border rounded focus:outline-none focus:ring-2 focus:ring-blue-500" />
        <button @click="saveEnvVar" :disabled="!editingKey.trim()"
                class="flex items-center justify-center gap-1 px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50">
          <Plus class="h-4 w-4" />
          Add
        </button>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="h-8 w-8 animate-spin rounded-full border-2 border-blue-600 border-t-transparent" />
    </div>

    <!-- Environment Variables List -->
    <div v-else class="bg-white border rounded-lg">
      <div class="px-4 py-3 border-b">
        <h3 class="font-medium">Environment Variables ({{ Object.keys(envVars).length }})</h3>
      </div>
      <div class="divide-y">
        <div v-for="(value, key) in envVars" :key="key" class="px-4 py-3 flex items-center justify-between hover:bg-gray-50">
          <div class="flex-1 min-w-0">
            <div class="font-medium text-sm">{{ key }}</div>
            <div class="flex items-center gap-2 mt-1">
              <code v-if="!isSecretKey(key) || showSecrets[key]" class="text-xs bg-gray-100 px-2 py-1 rounded truncate">
                {{ value }}
              </code>
              <code v-else class="text-xs bg-gray-100 px-2 py-1 rounded">
                ••••••••••••••••
              </code>
              <button v-if="isSecretKey(key)" @click="toggleSecret(key)" class="text-gray-400 hover:text-gray-600">
                <Eye v-if="!showSecrets[key]" class="h-3.5 w-3.5" />
                <EyeOff v-else class="h-3.5 w-3.5" />
              </button>
            </div>
          </div>
          <button @click="deleteEnvVar(key)" class="ml-4 text-gray-400 hover:text-red-600 p-1">
            <Trash2 class="h-4 w-4" />
          </button>
        </div>
      </div>
      <div v-if="Object.keys(envVars).length === 0" class="px-4 py-8 text-center text-gray-500">
        No environment variables configured
      </div>
    </div>
  </div>
</template>
