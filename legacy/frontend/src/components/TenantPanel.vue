<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { api, type Tenant, type TenantQuota, type TenantUsage, type TenantSecurity, type AuditEvent } from '../services/api'

type TabType = 'list' | 'details' | 'quota' | 'security' | 'audit'

const tenants = ref<Tenant[]>([])
const selectedTenant = ref<Tenant | null>(null)
const quota = ref<TenantQuota | null>(null)
const usage = ref<TenantUsage | null>(null)
const security = ref<TenantSecurity | null>(null)
const auditLogs = ref<AuditEvent[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const newTenantId = ref('')
const activeTab = ref<TabType>('list')
const editingQuota = ref(false)
const editingSecurity = ref(false)
const editedQuota = reactive<Partial<TenantQuota>>({})
const editedSecurity = reactive<Partial<TenantSecurity>>({})

const loadTenants = async () => {
  try {
    loading.value = true
    const response = await api.getTenants()
    tenants.value = response.tenants || []
    error.value = null
  } catch (err) {
    error.value = 'Failed to load tenants'
    console.error(err)
  } finally {
    loading.value = false
  }
}

const createTenant = async () => {
  if (!newTenantId.value.trim()) return
  try {
    loading.value = true
    await api.createTenant(newTenantId.value)
    newTenantId.value = ''
    await loadTenants()
  } catch (err) {
    error.value = 'Failed to create tenant'
    console.error(err)
  } finally {
    loading.value = false
  }
}

const deleteTenant = async (tenantId: string) => {
  if (!confirm(`Delete tenant "${tenantId}"?`)) return
  try {
    loading.value = true
    await api.deleteTenant(tenantId)
    if (selectedTenant.value?.id === tenantId) {
      selectedTenant.value = null
      activeTab.value = 'list'
    }
    await loadTenants()
  } catch (err) {
    error.value = 'Failed to delete tenant'
    console.error(err)
  } finally {
    loading.value = false
  }
}

const suspendTenant = async (tenantId: string) => {
  try {
    await api.suspendTenant(tenantId)
    await loadTenants()
  } catch (err) {
    error.value = 'Failed to suspend tenant'
    console.error(err)
  }
}

const resumeTenant = async (tenantId: string) => {
  try {
    await api.resumeTenant(tenantId)
    await loadTenants()
  } catch (err) {
    error.value = 'Failed to resume tenant'
    console.error(err)
  }
}

const viewTenantDetails = async (tenant: Tenant) => {
  selectedTenant.value = tenant
  activeTab.value = 'details'
  await loadTenantData(tenant.id)
}

const loadTenantData = async (tenantId: string) => {
  try {
    const [quotaRes, usageRes, securityRes] = await Promise.all([
      api.getQuota(tenantId),
      api.getUsage(tenantId),
      api.getSecurity(tenantId),
    ])
    quota.value = quotaRes.data
    usage.value = usageRes.data
    security.value = securityRes.data
    Object.assign(editedQuota, quotaRes.data)
    Object.assign(editedSecurity, securityRes.data)
  } catch (err) {
    console.error('Failed to load tenant details', err)
  }
}

const loadAuditLogs = async () => {
  if (!selectedTenant.value) return
  try {
    loading.value = true
    const response = await api.getTenantAuditLogs(selectedTenant.value.id, 100)
    auditLogs.value = response.data.events || []
  } catch (err) {
    error.value = 'Failed to load audit logs'
    console.error(err)
  } finally {
    loading.value = false
  }
}

const updateQuota = async () => {
  if (!selectedTenant.value) return
  try {
    loading.value = true
    await api.updateTenantQuota(selectedTenant.value.id, { ...editedQuota })
    await loadTenantData(selectedTenant.value.id)
    editingQuota.value = false
  } catch (err) {
    error.value = 'Failed to update quota'
    console.error(err)
  } finally {
    loading.value = false
  }
}

const updateSecurity = async () => {
  if (!selectedTenant.value) return
  try {
    loading.value = true
    await api.updateTenantSecurity(selectedTenant.value.id, { ...editedSecurity })
    await loadTenantData(selectedTenant.value.id)
    editingSecurity.value = false
  } catch (err) {
    error.value = 'Failed to update security policy'
    console.error(err)
  } finally {
    loading.value = false
  }
}

const getStateColor = (state: string) => {
  switch (state) {
    case 'ACTIVE': return 'bg-green-100 text-green-800'
    case 'SUSPENDED': return 'bg-yellow-100 text-yellow-800'
    case 'DESTROYED': return 'bg-red-100 text-red-800'
    default: return 'bg-gray-100 text-gray-800'
  }
}

const getEventIcon = (type: string) => {
  if (type.includes('CREATE')) return '🆕'
  if (type.includes('DELETE')) return '🗑️'
  if (type.includes('UPDATE')) return '✏️'
  if (type.includes('SUSPEND')) return '⏸️'
  if (type.includes('RESUME')) return '▶️'
  if (type.includes('SECURITY')) return '🔒'
  if (type.includes('NETWORK')) return '🌐'
  if (type.includes('EXEC')) return '⚡'
  return '📝'
}

const onCreateKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter') createTenant()
}

onMounted(loadTenants)
</script>

<template>
  <div class="bg-white rounded-lg shadow p-6">
    <!-- Header -->
    <div class="flex justify-between items-center mb-4">
      <h2 class="text-xl font-bold text-gray-800">Tenant Management</h2>
      <button
        @click="loadTenants"
        class="px-3 py-1 text-sm bg-gray-100 hover:bg-gray-200 rounded"
        :disabled="loading"
      >
        {{ loading ? 'Loading...' : 'Refresh' }}
      </button>
    </div>

    <!-- Error -->
    <div v-if="error" class="mb-4 p-3 bg-red-100 text-red-700 rounded">
      {{ error }}
    </div>

    <!-- Create Tenant -->
    <div class="mb-4 flex gap-2">
      <input
        v-model="newTenantId"
        type="text"
        placeholder="New tenant ID"
        class="flex-1 px-3 py-2 border rounded"
        @keydown="onCreateKeydown"
      />
      <button
        @click="createTenant"
        :disabled="!newTenantId.trim() || loading"
        class="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
      >
        Create
      </button>
    </div>

    <!-- Tabs -->
    <div class="flex gap-2 mb-4 border-b overflow-x-auto">
      <button
        @click="activeTab = 'list'"
        :class="[
          'px-4 py-2 whitespace-nowrap',
          activeTab === 'list' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-600'
        ]"
      >
        Tenants ({{ tenants.length }})
      </button>
      <template v-if="selectedTenant">
        <button
          @click="activeTab = 'details'"
          :class="[
            'px-4 py-2 whitespace-nowrap',
            activeTab === 'details' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-600'
          ]"
        >
          Overview
        </button>
        <button
          @click="activeTab = 'quota'"
          :class="[
            'px-4 py-2 whitespace-nowrap',
            activeTab === 'quota' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-600'
          ]"
        >
          Quota
        </button>
        <button
          @click="activeTab = 'security'"
          :class="[
            'px-4 py-2 whitespace-nowrap',
            activeTab === 'security' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-600'
          ]"
        >
          Security
        </button>
        <button
          @click="activeTab = 'audit'; loadAuditLogs()"
          :class="[
            'px-4 py-2 whitespace-nowrap',
            activeTab === 'audit' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-600'
          ]"
        >
          Audit Logs
        </button>
      </template>
    </div>

    <!-- Tenant List -->
    <div v-if="activeTab === 'list'" class="space-y-2">
      <p v-if="tenants.length === 0" class="text-gray-500 text-center py-4">No tenants found</p>
      <div
        v-for="tenant in tenants"
        :key="tenant.id"
        class="p-3 border rounded hover:bg-gray-50 cursor-pointer"
        @click="viewTenantDetails(tenant)"
      >
        <div class="flex justify-between items-start">
          <div>
            <div class="font-medium text-gray-900">{{ tenant.id }}</div>
            <div class="text-sm text-gray-500">
              Created: {{ new Date(tenant.createdAt).toLocaleString() }}
            </div>
            <div class="text-sm text-gray-500">
              Agents: {{ tenant.activeAgents }} | Sessions: {{ tenant.activeSessions }}
            </div>
          </div>
          <div class="flex items-center gap-2">
            <span :class="['px-2 py-1 text-xs rounded', getStateColor(tenant.state)]">
              {{ tenant.state }}
            </span>
            <button
              v-if="tenant.state === 'ACTIVE'"
              @click.stop="suspendTenant(tenant.id)"
              class="text-xs px-2 py-1 bg-yellow-100 text-yellow-700 rounded hover:bg-yellow-200"
            >
              Suspend
            </button>
            <button
              v-else
              @click.stop="resumeTenant(tenant.id)"
              class="text-xs px-2 py-1 bg-green-100 text-green-700 rounded hover:bg-green-200"
            >
              Resume
            </button>
            <button
              @click.stop="deleteTenant(tenant.id)"
              class="text-xs px-2 py-1 bg-red-100 text-red-700 rounded hover:bg-red-200"
            >
              Delete
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Tenant Details - Overview -->
    <div v-if="activeTab === 'details' && selectedTenant" class="space-y-4">
      <div class="flex justify-between items-center">
        <h3 class="text-lg font-semibold">{{ selectedTenant.id }}</h3>
        <button
          @click="activeTab = 'list'"
          class="text-sm text-blue-600 hover:underline"
        >
          ← Back to list
        </button>
      </div>

      <!-- Status Card -->
      <div class="grid grid-cols-2 gap-4">
        <div class="p-3 bg-gray-50 rounded">
          <div class="text-sm text-gray-500">State</div>
          <span :class="['px-2 py-1 text-xs rounded', getStateColor(selectedTenant.state)]">
            {{ selectedTenant.state }}
          </span>
        </div>
        <div class="p-3 bg-gray-50 rounded">
          <div class="text-sm text-gray-500">Active Agents</div>
          <div class="text-lg font-semibold">{{ selectedTenant.activeAgents }}</div>
        </div>
        <div class="p-3 bg-gray-50 rounded">
          <div class="text-sm text-gray-500">Active Sessions</div>
          <div class="text-lg font-semibold">{{ selectedTenant.activeSessions }}</div>
        </div>
        <div class="p-3 bg-gray-50 rounded">
          <div class="text-sm text-gray-500">Last Activity</div>
          <div class="text-sm">{{ new Date(selectedTenant.lastActivity).toLocaleString() }}</div>
        </div>
      </div>

      <!-- Usage -->
      <div v-if="usage" class="border rounded p-3">
        <h4 class="font-medium mb-2">Resource Usage</h4>
        <div class="space-y-2">
          <div>
            <div class="flex justify-between text-sm">
              <span>Storage</span>
              <span>{{ (usage.storage / 1024 / 1024).toFixed(2) }} MB</span>
            </div>
            <div class="w-full bg-gray-200 rounded-full h-2">
              <div
                class="bg-blue-600 h-2 rounded-full"
                :style="{ width: Math.min((usage.quota.storageUsage / usage.quota.maxStorage) * 100, 100) + '%' }"
              />
            </div>
          </div>
          <div>
            <div class="flex justify-between text-sm">
              <span>Daily Requests</span>
              <span>{{ usage.quota.dailyRequests }} / {{ usage.quota.maxDailyRequests }}</span>
            </div>
            <div class="w-full bg-gray-200 rounded-full h-2">
              <div
                class="bg-green-600 h-2 rounded-full"
                :style="{ width: Math.min((usage.quota.dailyRequests / usage.quota.maxDailyRequests) * 100, 100) + '%' }"
              />
            </div>
          </div>
          <div>
            <div class="flex justify-between text-sm">
              <span>Daily Tokens</span>
              <span>{{ usage.quota.dailyTokens }} / {{ usage.quota.maxDailyTokens }}</span>
            </div>
            <div class="w-full bg-gray-200 rounded-full h-2">
              <div
                class="bg-purple-600 h-2 rounded-full"
                :style="{ width: Math.min((usage.quota.dailyTokens / usage.quota.maxDailyTokens) * 100, 100) + '%' }"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Quota Configuration -->
    <div v-if="activeTab === 'quota' && selectedTenant" class="space-y-4">
      <div class="flex justify-between items-center">
        <h3 class="text-lg font-semibold">Quota Configuration - {{ selectedTenant.id }}</h3>
        <div class="flex gap-2">
          <template v-if="editingQuota">
            <button
              @click="editingQuota = false"
              class="px-3 py-1 text-sm bg-gray-100 rounded"
            >
              Cancel
            </button>
            <button
              @click="updateQuota"
              class="px-3 py-1 text-sm bg-green-600 text-white rounded"
            >
              Save
            </button>
          </template>
          <button
            v-else
            @click="editingQuota = true"
            class="px-3 py-1 text-sm bg-blue-600 text-white rounded"
          >
            Edit
          </button>
        </div>
      </div>

      <div v-if="quota" class="grid grid-cols-2 gap-4">
        <div
          v-for="item in [
            { key: 'maxDailyRequests', label: 'Max Daily Requests', type: 'number' },
            { key: 'maxDailyTokens', label: 'Max Daily Tokens', type: 'number' },
            { key: 'maxConcurrentAgents', label: 'Max Concurrent Agents', type: 'number' },
            { key: 'maxConcurrentSessions', label: 'Max Concurrent Sessions', type: 'number' },
            { key: 'maxStorageBytes', label: 'Max Storage (MB)', type: 'storage' },
            { key: 'maxMemoryBytes', label: 'Max Memory (MB)', type: 'memory' },
            { key: 'requestsPerSecond', label: 'Requests Per Second', type: 'number' },
            { key: 'requestsPerMinute', label: 'Requests Per Minute', type: 'number' },
            { key: 'maxToolCallsPerSession', label: 'Max Tool Calls Per Session', type: 'number' },
            { key: 'maxExecutionTimeSeconds', label: 'Max Execution Time (s)', type: 'number' },
          ]"
          :key="item.key"
          class="p-3 border rounded"
        >
          <label class="block text-sm text-gray-600 mb-1">{{ item.label }}</label>
          <input
            v-if="editingQuota"
            type="number"
            :value="(item.type === 'storage' || item.type === 'memory') 
              ? ((editedQuota[item.key as keyof TenantQuota] as number) / 1024 / 1024) 
              : editedQuota[item.key as keyof TenantQuota]"
            @input="(e) => {
              const value = parseInt((e.target as HTMLInputElement).value) || 0
              editedQuota[item.key as keyof TenantQuota] = (item.type === 'storage' || item.type === 'memory') 
                ? value * 1024 * 1024 
                : value
            }"
            class="w-full px-2 py-1 border rounded"
          />
          <div v-else class="font-medium">
            {{ (item.type === 'storage' || item.type === 'memory')
              ? ((quota[item.key as keyof TenantQuota] as number) / 1024 / 1024).toFixed(0)
              : quota[item.key as keyof TenantQuota] }}
          </div>
        </div>
      </div>
    </div>

    <!-- Security Policy -->
    <div v-if="activeTab === 'security' && selectedTenant" class="space-y-4">
      <div class="flex justify-between items-center">
        <h3 class="text-lg font-semibold">Security Policy - {{ selectedTenant.id }}</h3>
        <div class="flex gap-2">
          <template v-if="editingSecurity">
            <button
              @click="editingSecurity = false"
              class="px-3 py-1 text-sm bg-gray-100 rounded"
            >
              Cancel
            </button>
            <button
              @click="updateSecurity"
              class="px-3 py-1 text-sm bg-green-600 text-white rounded"
            >
              Save
            </button>
          </template>
          <button
            v-else
            @click="editingSecurity = true"
            class="px-3 py-1 text-sm bg-blue-600 text-white rounded"
          >
            Edit
          </button>
        </div>
      </div>

      <div v-if="security" class="space-y-4">
        <!-- Toggles -->
        <div class="grid grid-cols-3 gap-4">
          <div
            v-for="item in [
              { key: 'allowCodeExecution', label: 'Allow Code Execution' },
              { key: 'requireSandbox', label: 'Require Sandbox' },
              { key: 'allowNetworkAccess', label: 'Allow Network Access' },
            ]"
            :key="item.key"
            class="p-3 border rounded flex justify-between items-center"
          >
            <span class="text-sm">{{ item.label }}</span>
            <input
              v-if="editingSecurity"
              type="checkbox"
              v-model="editedSecurity[item.key as keyof TenantSecurity]"
              class="w-5 h-5"
            />
            <span
              v-else
              :class="[
                'px-2 py-1 text-xs rounded',
                security[item.key as keyof TenantSecurity] ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
              ]"
            >
              {{ security[item.key as keyof TenantSecurity] ? 'Enabled' : 'Disabled' }}
            </span>
          </div>
        </div>

        <!-- Allowed Languages -->
        <div class="border rounded p-3">
          <label class="block text-sm text-gray-600 mb-2">Allowed Languages</label>
          <input
            v-if="editingSecurity"
            :value="editedSecurity.allowedLanguages?.join(', ')"
            @input="(e) => {
              editedSecurity.allowedLanguages = (e.target as HTMLInputElement).value
                .split(',').map(s => s.trim()).filter(Boolean)
            }"
            class="w-full px-2 py-1 border rounded"
            placeholder="python, javascript, bash"
          />
          <div v-else class="flex flex-wrap gap-2">
            <span
              v-for="lang in security.allowedLanguages"
              :key="lang"
              class="px-2 py-1 bg-blue-100 text-blue-800 text-xs rounded"
            >
              {{ lang }}
            </span>
          </div>
        </div>

        <!-- Allowed Tools -->
        <div class="border rounded p-3">
          <label class="block text-sm text-gray-600 mb-2">Allowed Tools</label>
          <input
            v-if="editingSecurity"
            :value="editedSecurity.allowedTools?.join(', ')"
            @input="(e) => {
              editedSecurity.allowedTools = (e.target as HTMLInputElement).value
                .split(',').map(s => s.trim()).filter(Boolean)
            }"
            class="w-full px-2 py-1 border rounded"
            placeholder="read, write, exec"
          />
          <div v-else class="flex flex-wrap gap-2">
            <span
              v-for="tool in security.allowedTools"
              :key="tool"
              class="px-2 py-1 bg-green-100 text-green-800 text-xs rounded"
            >
              {{ tool }}
            </span>
          </div>
        </div>

        <!-- Denied Tools -->
        <div class="border rounded p-3">
          <label class="block text-sm text-gray-600 mb-2">Denied Tools</label>
          <input
            v-if="editingSecurity"
            :value="editedSecurity.deniedTools?.join(', ')"
            @input="(e) => {
              editedSecurity.deniedTools = (e.target as HTMLInputElement).value
                .split(',').map(s => s.trim()).filter(Boolean)
            }"
            class="w-full px-2 py-1 border rounded"
            placeholder="delete, rm"
          />
          <div v-else class="flex flex-wrap gap-2">
            <span
              v-for="tool in security.deniedTools"
              :key="tool"
              class="px-2 py-1 bg-red-100 text-red-800 text-xs rounded"
            >
              {{ tool }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- Audit Logs -->
    <div v-if="activeTab === 'audit' && selectedTenant" class="space-y-4">
      <div class="flex justify-between items-center">
        <h3 class="text-lg font-semibold">Audit Logs - {{ selectedTenant.id }}</h3>
        <button
          @click="loadAuditLogs"
          class="px-3 py-1 text-sm bg-gray-100 hover:bg-gray-200 rounded"
          :disabled="loading"
        >
          {{ loading ? 'Loading...' : 'Refresh' }}
        </button>
      </div>

      <div class="space-y-2 max-h-96 overflow-y-auto">
        <p v-if="auditLogs.length === 0" class="text-gray-500 text-center py-4">No audit logs found</p>
        <div
          v-for="(event, index) in auditLogs"
          :key="index"
          class="p-3 border rounded hover:bg-gray-50"
        >
          <div class="flex items-start gap-3">
            <span class="text-2xl">{{ getEventIcon(event.type) }}</span>
            <div class="flex-1">
              <div class="flex justify-between items-start">
                <span class="font-medium text-sm">{{ event.type }}</span>
                <span class="text-xs text-gray-500">
                  {{ new Date(event.timestamp).toLocaleString() }}
                </span>
              </div>
              <div class="text-xs text-gray-600 mt-1">
                <div v-for="(value, key) in event.details" :key="key">
                  <span class="font-medium">{{ key }}:</span> {{ String(value) }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
