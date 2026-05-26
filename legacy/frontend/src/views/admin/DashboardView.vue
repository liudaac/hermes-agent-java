<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { 
  Activity, Radio, Cpu, RotateCw, Download, AlertTriangle, 
  CheckCircle2, X, Loader2, Wifi, WifiOff
} from 'lucide-vue-next'
import { api, type StatusResponse, type SessionInfo, type ActionStatusResponse } from '../../services/api'

// State
const status = ref<StatusResponse | null>(null)
const sessions = ref<SessionInfo[]>([])
const loading = ref(true)
const pendingAction = ref<'restart' | 'update' | null>(null)
const activeAction = ref<'restart' | 'update' | null>(null)
const actionStatus = ref<ActionStatusResponse | null>(null)
const toast = ref<{ type: 'success' | 'error'; message: string } | null>(null)

// Poll interval
let pollInterval: number | null = null

// Computed
const activeSessions = computed(() => sessions.value.filter(s => s.is_active))
const recentSessions = computed(() => sessions.value.filter(s => !s.is_active).slice(0, 5))
const platforms = computed(() => Object.entries(status.value?.gateway_platforms ?? {}))

const alerts = computed(() => {
  const items: { message: string; detail?: string }[] = []
  if (status.value?.gateway_state === 'startup_failed') {
    items.push({
      message: 'Gateway failed to start',
      detail: status.value.gateway_exit_reason
    })
  }
  platforms.value.forEach(([name, info]) => {
    if (info.state === 'fatal' || info.state === 'disconnected') {
      items.push({
        message: `${name.charAt(0).toUpperCase() + name.slice(1)} ${info.state === 'fatal' ? 'error' : 'disconnected'}`,
        detail: info.error_message
      })
    }
  })
  return items
})

const statusItems = computed(() => {
  if (!status.value) return []
  return [
    {
      icon: Cpu,
      label: 'Agent Version',
      value: `v${status.value.version}`,
      badge: { text: 'Live', variant: 'success' as const }
    },
    {
      icon: Radio,
      label: 'Gateway',
      value: gatewayValue.value,
      badge: gatewayBadge.value
    },
    {
      icon: Activity,
      label: 'Active Sessions',
      value: status.value.active_sessions > 0 
        ? `${status.value.active_sessions} running` 
        : 'None running',
      badge: { 
        text: status.value.active_sessions > 0 ? 'Live' : 'Off', 
        variant: status.value.active_sessions > 0 ? 'success' : 'outline' as const 
      }
    }
  ]
})

const gatewayValue = computed(() => {
  if (!status.value) return 'Not running'
  if (status.value.gateway_running && status.value.gateway_health_url) {
    return status.value.gateway_health_url
  }
  if (status.value.gateway_running && status.value.gateway_pid) {
    return `PID ${status.value.gateway_pid}`
  }
  if (status.value.gateway_running) return 'Running (remote)'
  if (status.value.gateway_state === 'startup_failed') return 'Start failed'
  return 'Not running'
})

const gatewayBadge = computed(() => {
  const state = status.value?.gateway_state
  if (state === 'running') return { text: 'Running', variant: 'success' as const }
  if (state === 'starting') return { text: 'Starting', variant: 'warning' as const }
  if (state === 'startup_failed') return { text: 'Failed', variant: 'destructive' as const }
  if (state === 'stopped') return { text: 'Stopped', variant: 'outline' as const }
  return status.value?.gateway_running 
    ? { text: 'Running', variant: 'success' as const }
    : { text: 'Off', variant: 'outline' as const }
})

// Methods
async function loadData() {
  try {
    status.value = await api.getStatus()
    const resp = await api.getSessions(50)
    sessions.value = resp.sessions
  } catch (e) {
    console.error('Failed to load status:', e)
  } finally {
    loading.value = false
  }
}

async function runAction(action: 'restart' | 'update') {
  pendingAction.value = action
  actionStatus.value = null
  try {
    if (action === 'restart') {
      await api.restartGateway()
    } else {
      await api.updateHermes()
    }
    activeAction.value = action
    pollActionStatus()
  } catch (err) {
    const detail = err instanceof Error ? err.message : String(err)
    showToast('error', `Action failed: ${detail}`)
  } finally {
    pendingAction.value = null
  }
}

async function pollActionStatus() {
  if (!activeAction.value) return
  const name = activeAction.value === 'restart' ? 'gateway-restart' : 'hermes-update'
  try {
    const resp = await api.getActionStatus(name)
    actionStatus.value = resp
    if (!resp.running) {
      const ok = resp.exit_code === 0
      showToast(ok ? 'success' : 'error', ok ? 'Action finished' : `Action failed (exit ${resp.exit_code ?? '?'})`)
      activeAction.value = null
      return
    }
  } catch {
    // Keep polling
  }
  setTimeout(pollActionStatus, 1500)
}

function dismissLog() {
  activeAction.value = null
  actionStatus.value = null
}

function showToast(type: 'success' | 'error', message: string) {
  toast.value = { type, message }
  setTimeout(() => toast.value = null, 4000)
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

function getPlatformBadge(state: string) {
  switch (state) {
    case 'connected': return { variant: 'success' as const, label: 'Connected' }
    case 'disconnected': return { variant: 'warning' as const, label: 'Disconnected' }
    case 'fatal': return { variant: 'destructive' as const, label: 'Error' }
    default: return { variant: 'outline' as const, label: state }
  }
}

// Lifecycle
onMounted(() => {
  loadData()
  pollInterval = window.setInterval(loadData, 5000)
})

onUnmounted(() => {
  if (pollInterval) clearInterval(pollInterval)
})
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

    <!-- Alerts -->
    <div v-if="alerts.length > 0" class="bg-red-50 border border-red-200 rounded-lg p-4">
      <div class="flex items-start gap-3">
        <AlertTriangle class="h-5 w-5 text-red-600 shrink-0 mt-0.5" />
        <div class="space-y-2">
          <div v-for="(alert, i) in alerts" :key="i">
            <p class="text-sm font-medium text-red-800">{{ alert.message }}</p>
            <p v-if="alert.detail" class="text-xs text-red-600 mt-0.5">{{ alert.detail }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="flex items-center justify-center py-24">
      <div class="h-8 w-8 animate-spin rounded-full border-2 border-blue-600 border-t-transparent" />
    </div>

    <template v-else>
      <!-- Status Grid -->
      <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <div v-for="item in statusItems" :key="item.label" 
             class="bg-white border rounded-lg p-4 space-y-2">
          <div class="flex items-center justify-between">
            <span class="text-sm text-gray-600">{{ item.label }}</span>
            <component :is="item.icon" class="h-4 w-4 text-gray-400" />
          </div>
          <div class="text-2xl font-bold truncate" :title="item.value">{{ item.value }}</div>
          <span :class="[
            'inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium',
            item.badge.variant === 'success' ? 'bg-green-100 text-green-800' :
            item.badge.variant === 'warning' ? 'bg-yellow-100 text-yellow-800' :
            item.badge.variant === 'destructive' ? 'bg-red-100 text-red-800' :
            'bg-gray-100 text-gray-600'
          ]">
            <span v-if="item.badge.variant === 'success'" class="w-1.5 h-1.5 rounded-full bg-current animate-pulse" />
            {{ item.badge.text }}
          </span>
        </div>

        <!-- Actions -->
        <div class="bg-white border rounded-lg p-4 space-y-2">
          <div class="flex items-center justify-between">
            <span class="text-sm text-gray-600">Actions</span>
            <Activity class="h-4 w-4 text-gray-400" />
          </div>
          <div class="flex gap-2">
            <button @click="runAction('restart')" :disabled="pendingAction !== null || (activeAction !== null && actionStatus?.running !== false)"
                    class="flex-1 flex items-center justify-center gap-1 px-3 py-2 border rounded text-sm hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed">
              <RotateCw :class="['h-4 w-4', (pendingAction === 'restart' || (activeAction === 'restart' && actionStatus?.running)) && 'animate-spin']" />
              {{ activeAction === 'restart' && actionStatus?.running ? 'Restarting...' : 'Restart Gateway' }}
            </button>
            <button @click="runAction('update')" :disabled="pendingAction !== null || (activeAction !== null && actionStatus?.running !== false)"
                    class="flex-1 flex items-center justify-center gap-1 px-3 py-2 border rounded text-sm hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed">
              <Download :class="['h-4 w-4', (pendingAction === 'update' || (activeAction === 'update' && actionStatus?.running)) && 'animate-pulse']" />
              {{ activeAction === 'update' && actionStatus?.running ? 'Updating...' : 'Update Hermes' }}
            </button>
          </div>
        </div>
      </div>

      <!-- Action Log -->
      <div v-if="activeAction" class="bg-white border rounded-lg overflow-hidden">
        <div class="flex items-center justify-between px-4 py-2 border-b bg-gray-50">
          <div class="flex items-center gap-2">
            <Loader2 v-if="actionStatus?.running" class="h-4 w-4 animate-spin text-yellow-600" />
            <CheckCircle2 v-else-if="actionStatus?.exit_code === 0" class="h-4 w-4 text-green-600" />
            <AlertTriangle v-else-if="actionStatus" class="h-4 w-4 text-red-600" />
            <span class="text-sm font-medium">
              {{ activeAction === 'restart' ? 'Restart Gateway' : 'Update Hermes' }}
            </span>
            <span :class="[
              'text-xs px-2 py-0.5 rounded',
              actionStatus?.running ? 'bg-yellow-100 text-yellow-800' :
              actionStatus?.exit_code === 0 ? 'bg-green-100 text-green-800' :
              actionStatus ? 'bg-red-100 text-red-800' : 'bg-gray-100 text-gray-600'
            ]">
              {{ actionStatus?.running ? 'Running' : 
                 actionStatus?.exit_code === 0 ? 'Finished' : 
                 actionStatus ? `Failed (${actionStatus.exit_code ?? '?'})` : 'Loading' }}
            </span>
          </div>
          <button @click="dismissLog" class="text-gray-400 hover:text-gray-600">
            <X class="h-4 w-4" />
          </button>
        </div>
        <pre class="max-h-72 overflow-auto p-3 text-xs font-mono bg-gray-900 text-gray-100">
{{ actionStatus?.lines?.length ? actionStatus.lines.join('\n') : 'Waiting for output...' }}</pre>
      </div>

      <!-- Platforms -->
      <div v-if="platforms.length > 0" class="bg-white border rounded-lg">
        <div class="px-4 py-3 border-b">
          <h3 class="font-medium flex items-center gap-2">
            <Radio class="h-4 w-4 text-gray-500" />
            Connected Platforms
          </h3>
        </div>
        <div class="divide-y">
          <div v-for="[name, info] in platforms" :key="name" class="px-4 py-3 flex items-center justify-between">
            <div class="flex items-center gap-3">
              <Wifi v-if="info.state === 'connected'" class="h-4 w-4 text-green-600" />
              <WifiOff v-else-if="info.state === 'fatal'" class="h-4 w-4 text-red-600" />
              <WifiOff v-else class="h-4 w-4 text-yellow-600" />
              <div>
                <span class="font-medium capitalize">{{ name }}</span>
                <p v-if="info.error_message" class="text-xs text-red-600">{{ info.error_message }}</p>
                <p v-if="info.updated_at" class="text-xs text-gray-500">Updated {{ formatTimeAgo(info.updated_at) }}</p>
              </div>
            </div>
            <span :class="[
              'text-xs px-2 py-0.5 rounded',
              getPlatformBadge(info.state).variant === 'success' ? 'bg-green-100 text-green-800' :
              getPlatformBadge(info.state).variant === 'warning' ? 'bg-yellow-100 text-yellow-800' :
              'bg-red-100 text-red-800'
            ]">
              {{ getPlatformBadge(info.state).label }}
            </span>
          </div>
        </div>
      </div>

      <!-- Active Sessions -->
      <div v-if="activeSessions.length > 0" class="bg-white border rounded-lg">
        <div class="px-4 py-3 border-b">
          <h3 class="font-medium flex items-center gap-2">
            <Activity class="h-4 w-4 text-green-600" />
            Active Sessions ({{ activeSessions.length }})
          </h3>
        </div>
        <div class="divide-y">
          <div v-for="session in activeSessions" :key="session.id" class="px-4 py-3">
            <div class="flex items-center gap-2">
              <span class="font-medium truncate">{{ session.title || 'Untitled' }}</span>
              <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-800">
                <span class="w-1.5 h-1.5 rounded-full bg-current animate-pulse" />
                Live
              </span>
            </div>
            <p class="text-xs text-gray-500 mt-1">
              {{ session.model?.split('/').pop() }} · {{ session.message_count }} msgs · {{ formatTimeAgo(session.last_active) }}
            </p>
          </div>
        </div>
      </div>

      <!-- Recent Sessions -->
      <div v-if="recentSessions.length > 0" class="bg-white border rounded-lg">
        <div class="px-4 py-3 border-b">
          <h3 class="font-medium flex items-center gap-2">
            <Activity class="h-4 w-4 text-gray-500" />
            Recent Sessions
          </h3>
        </div>
        <div class="divide-y">
          <div v-for="session in recentSessions" :key="session.id" class="px-4 py-3">
            <div class="flex items-center justify-between">
              <div>
                <span class="font-medium truncate">{{ session.title || 'Untitled' }}</span>
                <p class="text-xs text-gray-500 mt-1">
                  {{ session.model?.split('/').pop() }} · {{ session.message_count }} msgs · {{ formatTimeAgo(session.last_active) }}
                </p>
                <p v-if="session.preview" class="text-xs text-gray-400 truncate mt-0.5">{{ session.preview }}</p>
              </div>
              <span class="text-xs px-2 py-0.5 rounded bg-gray-100 text-gray-600">
                {{ session.source || 'local' }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
