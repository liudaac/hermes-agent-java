<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Package, Power, RefreshCw, ExternalLink } from 'lucide-vue-next'
import { api, type SkillInfo } from '../../services/api'

const skills = ref<SkillInfo[]>([])
const loading = ref(true)
const toast = ref<{ type: 'success' | 'error'; message: string } | null>(null)

async function loadSkills() {
  loading.value = true
  try {
    skills.value = await api.getSkills()
  } catch (e) {
    showToast('error', 'Failed to load skills')
  } finally {
    loading.value = false
  }
}

async function toggleSkill(skill: SkillInfo) {
  try {
    await api.toggleSkill(skill.name, !skill.enabled)
    skill.enabled = !skill.enabled
    showToast('success', `${skill.name} ${skill.enabled ? 'enabled' : 'disabled'}`)
  } catch (e) {
    showToast('error', 'Failed to toggle skill')
  }
}

function showToast(type: 'success' | 'error', message: string) {
  toast.value = { type, message }
  setTimeout(() => toast.value = null, 3000)
}

onMounted(loadSkills)
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
        <Package class="h-5 w-5" />
        Skills
      </h2>
      <button @click="loadSkills" :disabled="loading"
              class="flex items-center gap-1 px-3 py-2 border rounded hover:bg-gray-50 disabled:opacity-50">
        <RefreshCw :class="['h-4 w-4', loading && 'animate-spin']" />
        Refresh
      </button>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="h-8 w-8 animate-spin rounded-full border-2 border-blue-600 border-t-transparent" />
    </div>

    <!-- Skills Grid -->
    <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      <div v-for="skill in skills" :key="skill.name" 
           :class="[
             'border rounded-lg p-4 transition-all',
             skill.enabled ? 'bg-white' : 'bg-gray-50 opacity-75'
           ]">
        <div class="flex items-start justify-between">
          <div>
            <h3 class="font-medium">{{ skill.name }}</h3>
            <p class="text-sm text-gray-500 mt-1">{{ skill.description || 'No description' }}</p>
            <p class="text-xs text-gray-400 mt-2">v{{ skill.version }}</p>
          </div>
          <button @click="toggleSkill(skill)"
                  :class="[
                    'p-2 rounded-full transition-colors',
                    skill.enabled ? 'bg-green-100 text-green-600 hover:bg-green-200' : 'bg-gray-200 text-gray-400 hover:bg-gray-300'
                  ]">
            <Power class="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>

    <!-- Empty State -->
    <div v-if="!loading && skills.length === 0" class="text-center py-12 text-gray-500">
      <Package class="h-12 w-12 mx-auto mb-4 opacity-30" />
      <p>No skills installed</p>
    </div>
  </div>
</template>
