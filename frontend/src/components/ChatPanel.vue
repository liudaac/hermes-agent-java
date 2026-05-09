<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { Send, Bot, User, Loader2 } from 'lucide-vue-next'
import { api } from '../services/api'

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: Date
  status?: 'sending' | 'sent' | 'error'
}

const messages = ref<Message[]>([])
const input = ref('')
const isLoading = ref(false)
const messagesContainer = ref<HTMLDivElement | null>(null)

const scrollToBottom = async () => {
  await nextTick()
  messagesContainer.value?.scrollTo({ top: messagesContainer.value.scrollHeight, behavior: 'smooth' })
}

watch(messages, scrollToBottom, { deep: true })

const handleSend = async () => {
  if (!input.value.trim() || isLoading.value) return

  const userMessage: Message = {
    id: Date.now().toString(),
    role: 'user',
    content: input.value.trim(),
    timestamp: new Date(),
    status: 'sent',
  }

  messages.value.push(userMessage)
  input.value = ''
  isLoading.value = true

  const assistantMessage: Message = {
    id: (Date.now() + 1).toString(),
    role: 'assistant',
    content: '',
    timestamp: new Date(),
    status: 'sending',
  }

  messages.value.push(assistantMessage)

  try {
    const request: MessageRequest = {
      content: userMessage.content,
      sessionId: 'frontend-session',
    }

    const response = await api.sendMessage(userMessage.content, 'frontend-session')
    
    const msg = messages.value.find(m => m.id === assistantMessage.id)
    if (msg) {
      msg.content = response.data.content || ''
      msg.status = 'sent'
    }
  } catch (error) {
    const msg = messages.value.find(m => m.id === assistantMessage.id)
    if (msg) {
      msg.content = 'Sorry, I encountered an error. Please try again.'
      msg.status = 'error'
    }
  } finally {
    isLoading.value = false
  }
}

const handleKeyDown = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}
</script>

<template>
  <div class="bg-white rounded-lg shadow-md flex flex-col h-[600px]">
    <!-- Header -->
    <div class="px-6 py-4 border-b border-gray-200">
      <h2 class="text-lg font-semibold text-gray-900 flex items-center gap-2">
        <Bot class="w-5 h-5 text-blue-500" />
        Chat with Hermes
      </h2>
      <p class="text-sm text-gray-500 mt-1">
        Send messages to the AI agent
      </p>
    </div>

    <!-- Messages -->
    <div ref="messagesContainer" class="flex-1 overflow-y-auto p-4 space-y-4">
      <div v-if="messages.length === 0" class="text-center text-gray-400 py-12">
        <Bot class="w-12 h-12 mx-auto mb-3 opacity-50" />
        <p>Start a conversation with Hermes</p>
        <p class="text-sm mt-1">Type your message below</p>
      </div>
      
      <div
        v-for="message in messages"
        :key="message.id"
        :class="['flex gap-3', message.role === 'user' ? 'flex-row-reverse' : '']"
      >
        <div
          :class="[
            'w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0',
            message.role === 'user' ? 'bg-blue-500 text-white' : 'bg-gray-100 text-gray-600'
          ]"
        >
          <User v-if="message.role === 'user'" class="w-4 h-4" />
          <Bot v-else class="w-4 h-4" />
        </div>
        <div
          :class="[
            'max-w-[70%] rounded-lg px-4 py-2',
            message.role === 'user' ? 'bg-blue-500 text-white' : 'bg-gray-100 text-gray-900'
          ]"
        >
          <div v-if="message.status === 'sending'" class="flex items-center gap-2">
            <Loader2 class="w-4 h-4 animate-spin" />
            <span class="text-sm">Thinking...</span>
          </div>
          <p v-else class="text-sm whitespace-pre-wrap">{{ message.content }}</p>
          <span class="text-xs opacity-70 mt-1 block">
            {{ message.timestamp.toLocaleTimeString() }}
          </span>
        </div>
      </div>
    </div>

    <!-- Input -->
    <div class="p-4 border-t border-gray-200">
      <div class="flex gap-2">
        <textarea
          v-model="input"
          @keydown="handleKeyDown"
          placeholder="Type your message..."
          class="flex-1 resize-none rounded-lg border border-gray-300 px-4 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent min-h-[44px] max-h-[120px]"
          rows="1"
        />
        <button
          @click="handleSend"
          :disabled="!input.trim() || isLoading"
          class="px-4 py-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 transition-colors"
        >
          <Loader2 v-if="isLoading" class="w-4 h-4 animate-spin" />
          <Send v-else class="w-4 h-4" />
          Send
        </button>
      </div>
    </div>
  </div>
</template>
