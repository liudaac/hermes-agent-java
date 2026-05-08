<script setup lang="ts">
import { Bot, Settings, MessageSquare, Terminal } from 'lucide-vue-next'
</script>

<template>
  <div class="min-h-screen bg-gray-50 flex flex-col">
    <!-- Header -->
    <header class="bg-white border-b border-gray-200 sticky top-0 z-50">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="flex items-center justify-between h-16">
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 bg-gradient-to-br from-blue-500 to-purple-600 rounded-xl flex items-center justify-center">
              <Bot class="w-6 h-6 text-white" />
            </div>
            <div>
              <h1 class="text-xl font-bold text-gray-900">Hermes</h1>
              <p class="text-xs text-gray-500">AI Agent Dashboard</p>
            </div>
          </div>
          
          <nav class="flex items-center gap-1">
            <button class="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium bg-blue-50 text-blue-600">
              <MessageSquare class="w-4 h-4" />
              Chat
            </button>
            <button class="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors">
              <Terminal class="w-4 h-4" />
              Terminal
            </button>
            <button class="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors">
              <Settings class="w-4 h-4" />
              Settings
            </button>
          </nav>

          <div class="flex items-center gap-3">
            <a
              href="https://github.com/liudaac/hermes-agent-java"
              target="_blank"
              rel="noopener noreferrer"
              class="text-gray-400 hover:text-gray-600 transition-colors text-sm"
            >
              GitHub
            </a>
          </div>
        </div>
      </div>
    </header>

    <!-- Main Content -->
    <main class="flex-1 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 w-full">
      <slot />
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
