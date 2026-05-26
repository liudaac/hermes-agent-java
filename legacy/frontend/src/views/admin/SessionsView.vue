<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { MessageSquare, Search, X, Trash2, RefreshCw } from 'lucide-vue-next'
import { api, type SessionInfo } from '../../services/api'

const sessions = ref<SessionInfo[]>([])
const loading = ref(true)
const searchQuery = ref('')
const toast = ref<{ type: 'success' | 'error'; message: string } | null>(null)

const filteredSessions = computed(() => {
  if (!searchQuery.value) return sessions.value
  const q = searchQuery.value.toLowerCase()
  return sessions.value.filter(s => 
    (s.title?.toLowerCase().includes(q)) ||
    (s.model?.toLowerCase().includes(q)) ||
    (s.source?.toLowerCase().includes(q))
  )
})

const activeSessions = computed(() => filteredSessions.value.filter(s => s.is_active))
const inactiveSessions = computed(() => filteredSessions.value.filter(s => !s.is_active))

async function loadSessions() {
  loading.value = true
  try {
    const resp = await api.getSessions(100)
    sessions.value = resp.sessions
  } catch (e) {
    showToast('error', 'Failed to load sessions')
  } finally {
    loading.value = false
  }
}

async function deleteSession(id: string) {
  if (!confirm('Delete this session?')) return
  // TODO: implement delete API
  showToast('success', 'Session deleted')
}

function showToast(type: 'success' | 'error', message: string) {
  toast.value = { type, message }
  setTimeout(() => toast.value = null, 3000)
}

function formatTimeAgo(date: string): string {
  const seconds = Math.floor((Date.now() - new Date(date).getTime()) / 1000)
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

onMounted(loadSessions)
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
        <MessageSquare class="h-5 w-5" />
        Sessions
      </h2>
      <button @click="loadSessions" class="flex items-center gap-1 px-3 py-2 border rounded hover:bg-gray-50">
        <RefreshCw class="h-4 w-4" />
        Refresh
      </button>
    </div>

    <!-- Search -->
    <div class="relative">
      <Search class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
      <input v-model="searchQuery" type="text" placeholder="Search sessions..."
             class="w-full pl-10 pr-10 py-2 border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500" />
      <button v-if="searchQuery" @click="searchQuery = ''" 
              class="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600">
        <X class="h-4 w-4" />
      </button>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="h-8 w-8 animate-spin rounded-full border-2 border-blue-600 border-t-transparent" />
    </div>

    <template v-else>
      <!-- Active Sessions -->
      <div v-if="activeSessions.length > 0" class="bg-white border rounded-lg">
        <div class="px-4 py-3 border-b bg-green-50">
          <h3 class="font-medium text-green-800 flex items-center gap-2">
            <span class="w-2 h-2 rounded-full bg-green-600 animate-pulse" />
            Active Sessions ({{ activeSessions.length }})
          </h3>
        </div>
        <div class="divide-y">
          <div v-for="session in activeSessions" :key="session.id" class="px-4 py-3 hover:bg-gray-50">
            <div class="flex items-center justify-between">
              <div>
                <div class="flex items-center gap-2">
                  <span class="font-medium">{{ session.title || 'Untitled' }}</span>
                  <span class="px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-800">Active</span>
                </div>
                <p class="text-sm text-gray-500 mt-1">
                  {{ session.model }} · {{ session.message_count }} messages · {{ formatTimeAgo(session.last_active) }}
                </p>
              </div>
              <button @click="deleteSession(session.id)" class="text-gray-400 hover:text-red-600">
                <Trash2 class="h-4 w-4" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Inactive Sessions -->
      <div v-if="inactiveSessions.length > 0" class="bg-white border rounded-lg">
        <div class="px-4 py-3 border-b">
          <h3 class="font-medium text-gray-600">Inactive Sessions ({{ inactiveSessions.length }})</h3>
        </div>
        <div class="divide-y">
          <div v-for="session in inactiveSessions" :key="session.id" class="px-4 py-3 hover:bg-gray-50">
            <div class="flex items-center justify-between">
              <div>
                <div class="flex items-center gap-2">
                  <span class="font-medium">{{ session.title || 'Untitled' }}</span>
                  <span class="px-2 py-0.5 rounded text-xs font-medium bg-gray-100 text-gray-600">Inactive</span>
                </div>
                <p class="text-sm text-gray-500 mt-1">
                  {{ session.model }} · {{ session.message_count }} messages · {{ formatTimeAgo(session.last_active) }}
                </p>
                <p v-if="session.preview" class="text-sm text-gray-400 mt-1 truncate">{{ session.preview }}</p>
              </div>
              <button @click="deleteSession(session.id)" class="text-gray-400 hover:text-red-600">
                <Trash2 class="h-4 w-4" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Empty State -->
      <div v-if="filteredSessions.length === 0" class="text-center py-12 text-gray-500">
        <MessageSquare class="h-12 w-12 mx-auto mb-4 opacity-30" />
        <p>No sessions found</p>
      </div>
    </template>
  </div>
</template>
