<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { 
  Bot, MessageSquare, Users, Activity, BarChart3, FileText, 
  Clock, Package, Settings, KeyRound, Menu, X
} from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const mobileMenuOpen = ref(false)

const userNavItems = [
  { path: '/', name: 'Chat', icon: MessageSquare },
  { path: '/tenants', name: 'Tenants', icon: Users }
]

const adminNavItems = [
  { path: '/admin', name: 'Dashboard', icon: Activity },
  { path: '/admin/sessions', name: 'Sessions', icon: MessageSquare },
  { path: '/admin/analytics', name: 'Analytics', icon: BarChart3 },
  { path: '/admin/logs', name: 'Logs', icon: FileText },
  { path: '/admin/cron', name: 'Cron', icon: Clock },
  { path: '/admin/skills', name: 'Skills', icon: Package },
  { path: '/admin/config', name: 'Config', icon: Settings },
  { path: '/admin/env', name: 'API Keys', icon: KeyRound }
]

const isUserRoute = computed(() => !route.path.startsWith('/admin'))

function isActive(path: string) {
  return route.path === path || route.path.startsWith(path + '/')
}

function navigate(path: string) {
  router.push(path)
  mobileMenuOpen.value = false
}
</script>

<template>
  <div class="min-h-screen bg-gray-50 flex flex-col">
    <!-- Header -->
    <header class="bg-white border-b border-gray-200 sticky top-0 z-50">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="flex items-center justify-between h-16">
          <!-- Logo -->
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 bg-gradient-to-br from-blue-500 to-purple-600 rounded-xl flex items-center justify-center">
              <Bot class="w-6 h-6 text-white" />
            </div>
            <div>
              <h1 class="text-xl font-bold text-gray-900">Hermes</h1>
              <p class="text-xs text-gray-500">AI Agent Dashboard</p>
            </div>
          </div>
          
          <!-- Desktop Nav -->
          <nav class="hidden md:flex items-center gap-1">
            <!-- User Routes -->
            <template v-for="item in userNavItems" :key="item.path">
              <button @click="navigate(item.path)"
                      :class="[
                        'flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors',
                        isActive(item.path) 
                          ? 'bg-blue-50 text-blue-600' 
                          : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
                      ]">
                <component :is="item.icon" class="w-4 h-4" />
                {{ item.name }}
              </button>
            </template>
            
            <!-- Divider -->
            <div class="w-px h-6 bg-gray-200 mx-2"></div>
            
            <!-- Admin Routes -->
            <template v-for="item in adminNavItems" :key="item.path">
              <button @click="navigate(item.path)"
                      :class="[
                        'flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium transition-colors',
                        isActive(item.path) 
                          ? 'bg-purple-50 text-purple-600' 
                          : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
                      ]">
                <component :is="item.icon" class="w-4 h-4" />
                <span class="hidden lg:inline">{{ item.name }}</span>
              </button>
            </template>
          </nav>

          <!-- Mobile Menu Button -->
          <div class="flex items-center gap-3">
            <button @click="mobileMenuOpen = !mobileMenuOpen" class="md:hidden p-2 rounded-lg hover:bg-gray-100">
              <Menu v-if="!mobileMenuOpen" class="w-5 h-5" />
              <X v-else class="w-5 h-5" />
            </button>
            <a href="https://github.com/liudaac/hermes-agent-java" target="_blank"
               class="hidden sm:block text-gray-400 hover:text-gray-600 transition-colors text-sm">
              GitHub
            </a>
          </div>
        </div>
      </div>
      
      <!-- Mobile Menu -->
      <div v-if="mobileMenuOpen" class="md:hidden border-t bg-white">
        <div class="px-4 py-3 space-y-2">
          <p class="text-xs font-medium text-gray-400 uppercase">User</p>
          <button v-for="item in userNavItems" :key="item.path" @click="navigate(item.path)"
                  :class="[
                    'w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm',
                    isActive(item.path) ? 'bg-blue-50 text-blue-600' : 'text-gray-600'
                  ]">
            <component :is="item.icon" class="w-4 h-4" />
            {{ item.name }}
          </button>
          <p class="text-xs font-medium text-gray-400 uppercase pt-2">Admin</p>
          <button v-for="item in adminNavItems" :key="item.path" @click="navigate(item.path)"
                  :class="[
                    'w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm',
                    isActive(item.path) ? 'bg-purple-50 text-purple-600' : 'text-gray-600'
                  ]">
            <component :is="item.icon" class="w-4 h-4" />
            {{ item.name }}
          </button>
        </div>
      </div>
    </header>

    <!-- Main Content -->
    <main class="flex-1 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 w-full">
      <router-view />
    </main>

    <!-- Footer -->
    <footer class="bg-white border-t border-gray-200">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
        <div class="flex items-center justify-between text-sm text-gray-500">
          <p>Hermes Agent v0.1.0</p>
          <p>Connected to localhost:8080</p>
        </div>
      </div>
    </footer>
  </div>
</template>
