<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Clock, Plus, Trash2, Power, Play, RefreshCw } from 'lucide-vue-next'
import { api, type CronJob } from '../../services/api'

const jobs = ref<CronJob[]>([])
const loading = ref(true)
const showAddModal = ref(false)
const toast = ref<{ type: 'success' | 'error'; message: string } | null>(null)

// New job form
const newJob = ref({
  name: '',
  schedule: '',
  command: '',
  enabled: true
})

async function loadJobs() {
  loading.value = true
  try {
    jobs.value = await api.getCronJobs()
  } catch (e) {
    showToast('error', 'Failed to load cron jobs')
  } finally {
    loading.value = false
  }
}

async function createJob() {
  try {
    await api.createCronJob(newJob.value)
    showAddModal.value = false
    newJob.value = { name: '', schedule: '', command: '', enabled: true }
    await loadJobs()
    showToast('success', 'Cron job created')
  } catch (e) {
    showToast('error', 'Failed to create cron job')
  }
}

async function toggleJob(job: CronJob) {
  try {
    await api.updateCronJob(job.id, { enabled: !job.enabled })
    job.enabled = !job.enabled
    showToast('success', `Job ${job.enabled ? 'enabled' : 'disabled'}`)
  } catch (e) {
    showToast('error', 'Failed to toggle job')
  }
}

async function deleteJob(id: string) {
  if (!confirm('Delete this cron job?')) return
  try {
    await api.deleteCronJob(id)
    await loadJobs()
    showToast('success', 'Cron job deleted')
  } catch (e) {
    showToast('error', 'Failed to delete cron job')
  }
}

function showToast(type: 'success' | 'error', message: string) {
  toast.value = { type, message }
  setTimeout(() => toast.value = null, 3000)
}

function formatDate(date?: string): string {
  if (!date) return 'Never'
  return new Date(date).toLocaleString()
}

onMounted(loadJobs)
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
        <Clock class="h-5 w-5" />
        Cron Jobs
      </h2>
      <div class="flex items-center gap-2">
        <button @click="loadJobs" :disabled="loading"
                class="flex items-center gap-1 px-3 py-2 border rounded hover:bg-gray-50 disabled:opacity-50">
          <RefreshCw :class="['h-4 w-4', loading && 'animate-spin']" />
          Refresh
        </button>
        <button @click="showAddModal = true"
                class="flex items-center gap-1 px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700">
          <Plus class="h-4 w-4" />
          Add Job
        </button>
      </div>
    </div>

    <!-- Add Modal -->
    <div v-if="showAddModal" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div class="bg-white rounded-lg p-6 w-full max-w-md mx-4">
        <h3 class="text-lg font-bold mb-4">Add Cron Job</h3>
        <div class="space-y-4">
          <div>
            <label class="block text-sm font-medium mb-1">Name</label>
            <input v-model="newJob.name" type="text" placeholder="Daily Report"
                   class="w-full px-3 py-2 border rounded focus:outline-none focus:ring-2 focus:ring-blue-500" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">Schedule (Cron)</label>
            <input v-model="newJob.schedule" type="text" placeholder="0 9 * * *"
                   class="w-full px-3 py-2 border rounded focus:outline-none focus:ring-2 focus:ring-blue-500" />
            <p class="text-xs text-gray-500 mt-1">Example: 0 9 * * * (daily at 9am)</p>
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">Command</label>
            <textarea v-model="newJob.command" rows="2" placeholder="/path/to/script.sh"
                      class="w-full px-3 py-2 border rounded focus:outline-none focus:ring-2 focus:ring-blue-500"></textarea>
          </div>
        </div>
        <div class="flex justify-end gap-2 mt-6">
          <button @click="showAddModal = false" class="px-4 py-2 border rounded hover:bg-gray-50">Cancel</button>
          <button @click="createJob" :disabled="!newJob.name || !newJob.schedule || !newJob.command"
                  class="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50">
            Create
          </button>
        </div>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="h-8 w-8 animate-spin rounded-full border-2 border-blue-600 border-t-transparent" />
    </div>

    <!-- Jobs List -->
    <div v-else class="bg-white border rounded-lg">
      <div class="divide-y">
        <div v-for="job in jobs" :key="job.id" class="px-4 py-4 flex items-center justify-between hover:bg-gray-50">
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2">
              <span class="font-medium">{{ job.name }}</span>
              <span :class="[
                'text-xs px-2 py-0.5 rounded',
                job.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-600'
              ]">
                {{ job.enabled ? 'Enabled' : 'Disabled' }}
              </span>
            </div>
            <p class="text-sm text-gray-500 mt-1 font-mono">{{ job.schedule }}</p>
            <p class="text-xs text-gray-400 mt-1 truncate">{{ job.command }}</p>
            <div class="flex gap-4 mt-2 text-xs text-gray-400">
              <span>Last: {{ formatDate(job.last_run) }}</span>
              <span>Next: {{ formatDate(job.next_run) }}</span>
            </div>
          </div>
          <div class="flex items-center gap-1 ml-4">
            <button @click="toggleJob(job)"
                    :class="[
                      'p-2 rounded hover:bg-gray-100',
                      job.enabled ? 'text-green-600' : 'text-gray-400'
                    ]">
              <Power class="h-4 w-4" />
            </button>
            <button @click="deleteJob(job.id)" class="p-2 rounded hover:bg-gray-100 text-gray-400 hover:text-red-600">
              <Trash2 class="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>
      <div v-if="jobs.length === 0" class="px-4 py-8 text-center text-gray-500">
        <Clock class="h-12 w-12 mx-auto mb-4 opacity-30" />
        <p>No cron jobs configured</p>
      </div>
    </div>
  </div>
</template>
