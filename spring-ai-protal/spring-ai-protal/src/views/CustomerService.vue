<template>
  <div class="customer-service" :class="{ dark: isDark }">
    <div class="chat-container">
      <aside class="sidebar">
        <div class="history-header">
          <h2>咨询记录</h2>
          <button class="new-chat" @click="startNewChat">
            <PlusIcon class="icon" />
            新咨询
          </button>
        </div>

        <div class="history-list">
          <div
            v-for="chat in chatHistory"
            :key="chat.id"
            class="history-item"
            :class="{ active: currentChatId === chat.id }"
            @click="loadChat(chat.id)"
          >
            <ChatBubbleLeftRightIcon class="icon" />
            <span class="title">{{ chat.title || '新咨询' }}</span>
          </div>
        </div>
      </aside>

      <main class="chat-main">
        <header class="service-header">
          <div class="service-info">
            <ComputerDesktopIcon class="avatar" />
            <div class="info">
              <h3>录合同大师</h3>
              <p>录合同客服</p>
            </div>
          </div>
        </header>

        <div class="messages" ref="messagesRef">
          <ChatMessage
            v-for="(message, index) in currentMessages"
            :key="index"
            :message="message"
            :is-stream="isStreaming && index === currentMessages.length - 1"
          />
        </div>

        <div class="input-area">
          <textarea
            ref="inputRef"
            v-model="userInput"
            rows="1"
            placeholder="请输入您的录合同需求..."
            @input="adjustTextareaHeight"
            @keydown.enter.prevent="handleEnterKey"
          ></textarea>

          <button
            class="send-button"
            :class="{ 'stop-button': isStreaming }"
            :disabled="!isStreaming && !userInput.trim()"
            :title="isStreaming ? '停止生成' : '发送消息'"
            @click="isStreaming ? stopGeneration() : sendMessage()"
          >
            <XMarkIcon v-if="isStreaming" class="icon" />
            <PaperAirplaneIcon v-else class="icon" />
          </button>
        </div>
      </main>
    </div>

    <div v-if="showBookingModal" class="booking-modal">
      <div class="modal-content">
        <h3>合同创建成功！</h3>
        <div class="booking-info" v-html="bookingInfo"></div>
        <button @click="showBookingModal = false">确定</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { useDark } from '@vueuse/core'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import {
  ChatBubbleLeftRightIcon,
  PaperAirplaneIcon,
  PlusIcon,
  ComputerDesktopIcon,
  XMarkIcon
} from '@heroicons/vue/24/outline'
import ChatMessage from '../components/ChatMessage.vue'
import { chatAPI } from '../services/api'

const isDark = useDark()
const messagesRef = ref(null)
const inputRef = ref(null)
const userInput = ref('')
const isStreaming = ref(false)
const currentChatId = ref(null)
const currentMessages = ref([])
const chatHistory = ref([])
const showBookingModal = ref(false)
const bookingInfo = ref('')
const currentRequestController = ref(null)
const currentReader = ref(null)
const stoppedByUser = ref(false)

marked.setOptions({
  breaks: true,
  gfm: true,
  sanitize: false
})

const adjustTextareaHeight = () => {
  const textarea = inputRef.value
  if (!textarea) return
  textarea.style.height = 'auto'
  textarea.style.height = `${textarea.scrollHeight}px`
}

const scrollToBottom = async () => {
  await nextTick()
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

const splitSseEvents = (state, chunk) => {
  state.buffer += chunk
  const events = []

  while (true) {
    const boundaryIndex = state.buffer.indexOf('\n\n')
    if (boundaryIndex === -1) break

    const rawEvent = state.buffer.slice(0, boundaryIndex)
    state.buffer = state.buffer.slice(boundaryIndex + 2)
    events.push(rawEvent)
  }

  return events
}

const eventToText = (rawEvent) => {
  const normalizedEvent = rawEvent.replace(/\r/g, '')
  const dataLines = normalizedEvent
    .split('\n')
    .filter(line => line.startsWith('data:'))
    .map(line => line.slice(5).replace(/^\s/, ''))

  if (dataLines.length > 0) {
    return dataLines.join('\n')
  }

  return normalizedEvent
}

const parseStreamChunk = (state, chunk) => {
  if (!chunk) return []

  if (chunk.includes('data:') || state.buffer.includes('data:')) {
    return splitSseEvents(state, chunk).map(eventToText)
  }

  return [chunk]
}

const flushStreamBuffer = (state) => {
  if (!state.buffer.trim()) return ''
  const text = eventToText(state.buffer)
  state.buffer = ''
  return text
}

const handleEnterKey = () => {
  if (isStreaming.value) {
    stopGeneration()
    return
  }
  sendMessage()
}

const stopGeneration = async () => {
  if (!isStreaming.value) return

  stoppedByUser.value = true

  try {
    await currentReader.value?.cancel()
  } catch (error) {
    console.warn('取消读取流失败', error)
  }

  currentRequestController.value?.abort()
}

const waitForStreamingIdle = async (timeout = 3000) => {
  const start = Date.now()
  while (isStreaming.value && Date.now() - start < timeout) {
    await new Promise(resolve => setTimeout(resolve, 50))
  }
}

const updateAssistantMessage = (index, baseMessage, content) => {
  currentMessages.value.splice(index, 1, {
    ...baseMessage,
    content,
    isMarkdown: true
  })
}

const showContractSuccessModalIfNeeded = (content) => {
  const contractMatch = content.match(/(?:合同(?:编号|单号)|contractCode|contractId)[：:\s]*([A-Za-z0-9_-]+)/i)

  if (!content.includes('合同创建成功') && !contractMatch) {
    return
  }

  const contractNo = contractMatch?.[1] || '创建接口未返回合同编号'
  bookingInfo.value = DOMPurify.sanitize(
    marked.parse(`合同编号：${contractNo}`),
    {
      ADD_TAGS: ['code', 'pre', 'span'],
      ADD_ATTR: ['class', 'language']
    }
  )
  showBookingModal.value = true
}

const sendMessage = async (content) => {
  if (isStreaming.value || (!content && !userInput.value.trim())) return

  const messageContent = content || userInput.value.trim()
  const userMessage = {
    role: 'user',
    content: messageContent,
    timestamp: new Date()
  }

  currentMessages.value.push(userMessage)

  if (!content) {
    userInput.value = ''
    adjustTextareaHeight()
  }

  const assistantMessage = {
    role: 'assistant',
    content: '',
    timestamp: new Date(),
    isMarkdown: true
  }

  currentMessages.value.push(assistantMessage)
  const assistantIndex = currentMessages.value.length - 1

  isStreaming.value = true
  stoppedByUser.value = false
  await scrollToBottom()

  let accumulatedContent = ''
  const streamState = { buffer: '' }

  try {
    currentRequestController.value = new AbortController()
    const reader = await chatAPI.sendServiceMessage(messageContent, currentChatId.value, {
      signal: currentRequestController.value.signal
    })
    currentReader.value = reader
    const decoder = new TextDecoder('utf-8')

    while (true) {
      let result
      try {
        result = await reader.read()
      } catch (readError) {
        if (readError?.name === 'AbortError' || stoppedByUser.value) {
          break
        }
        throw readError
      }

      const { value, done } = result
      if (done) {
        const finalChunk = decoder.decode()
        const finalMessages = parseStreamChunk(streamState, finalChunk)
        accumulatedContent += finalMessages.join('')
        accumulatedContent += flushStreamBuffer(streamState)
        break
      }

      const decodedChunk = decoder.decode(value, { stream: true })
      const messages = parseStreamChunk(streamState, decodedChunk)
      if (messages.length === 0) continue

      accumulatedContent += messages.join('')
      updateAssistantMessage(assistantIndex, assistantMessage, accumulatedContent)
      await scrollToBottom()
    }

    if (stoppedByUser.value) {
      updateAssistantMessage(assistantIndex, assistantMessage, accumulatedContent || '已停止生成')
      return
    }

    if (!accumulatedContent.trim()) {
      accumulatedContent = '服务端没有返回内容，请检查后端模型配置或稍后重试。'
      updateAssistantMessage(assistantIndex, assistantMessage, accumulatedContent)
      return
    }

    showContractSuccessModalIfNeeded(accumulatedContent)
  } catch (error) {
    if (error?.name === 'AbortError' || stoppedByUser.value) {
      updateAssistantMessage(assistantIndex, assistantMessage, accumulatedContent || '已停止生成')
      return
    }

    console.error('发送消息失败', error)
    updateAssistantMessage(
      assistantIndex,
      assistantMessage,
      `请求失败：${error?.message || '请稍后重试。'}`
    )
  } finally {
    isStreaming.value = false
    currentReader.value = null
    currentRequestController.value = null
    await scrollToBottom()
  }
}

const loadChat = async (chatId) => {
  currentChatId.value = chatId
  try {
    const messages = await chatAPI.getChatMessages(chatId, 'service')
    currentMessages.value = messages.map(message => ({
      ...message,
      isMarkdown: message.role === 'assistant'
    }))
  } catch (error) {
    console.error('加载对话消息失败', error)
    currentMessages.value = []
  }
}

const loadChatHistory = async () => {
  try {
    const history = await chatAPI.getChatHistory('service')
    chatHistory.value = history || []
    if (history?.length) {
      await loadChat(history[0].id)
    } else {
      await startNewChat()
    }
  } catch (error) {
    console.error('加载聊天历史失败', error)
    chatHistory.value = []
    await startNewChat()
  }
}

const startNewChat = async () => {
  if (isStreaming.value) {
    await stopGeneration()
    await waitForStreamingIdle()
  }

  const newChatId = Date.now().toString()
  currentChatId.value = newChatId
  currentMessages.value = []

  chatHistory.value = [
    {
      id: newChatId,
      title: `咨询 ${newChatId.slice(-6)}`
    },
    ...chatHistory.value
  ]

  await nextTick()
  await sendMessage('你好')
}

onMounted(() => {
  loadChatHistory()
  adjustTextareaHeight()
})
</script>

<style scoped lang="scss">
.customer-service {
  position: fixed;
  top: 64px;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  background: var(--bg-color);
  overflow: hidden;

  .chat-container {
    flex: 1;
    display: flex;
    max-width: 1800px;
    width: 100%;
    height: 100%;
    margin: 0 auto;
    padding: 1.5rem 2rem;
    gap: 1.5rem;
    overflow: hidden;
  }

  .sidebar {
    width: 300px;
    display: flex;
    flex-direction: column;
    background: rgba(255, 255, 255, 0.95);
    backdrop-filter: blur(10px);
    border-radius: 1rem;
    box-shadow: 0 4px 6px rgba(0, 0, 0, 0.05);
  }

  .history-header {
    flex-shrink: 0;
    padding: 1rem;
    display: flex;
    justify-content: space-between;
    align-items: center;

    h2 {
      font-size: 1.25rem;
      margin: 0;
    }
  }

  .new-chat {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.5rem 1rem;
    border-radius: 0.5rem;
    background: #333;
    color: #fff;
    border: none;
    cursor: pointer;
    transition: background-color 0.3s;

    &:hover {
      background: #000;
    }
  }

  .history-list {
    flex: 1;
    overflow-y: auto;
    padding: 0 1rem 1rem;
  }

  .history-item {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.75rem;
    border-radius: 0.5rem;
    cursor: pointer;
    transition: background-color 0.3s;

    &:hover {
      background: rgba(0, 0, 0, 0.05);
    }

    &.active {
      background: rgba(0, 0, 0, 0.1);
    }

    .title {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .icon {
    width: 1.25rem;
    height: 1.25rem;
  }

  .chat-main {
    flex: 1;
    display: flex;
    flex-direction: column;
    background: rgba(255, 255, 255, 0.95);
    backdrop-filter: blur(10px);
    border-radius: 1rem;
    box-shadow: 0 4px 6px rgba(0, 0, 0, 0.05);
    overflow: hidden;
  }

  .service-header {
    flex-shrink: 0;
    padding: 1rem 2rem;
    border-bottom: 1px solid rgba(0, 0, 0, 0.05);
    background: rgba(255, 255, 255, 0.98);
  }

  .service-info {
    display: flex;
    align-items: center;
    gap: 1rem;

    .avatar {
      width: 48px;
      height: 48px;
      color: #333;
      padding: 6px;
      background: #f0f0f0;
      border-radius: 12px;
      transition: all 0.3s ease;

      &:hover {
        background: #e0e0e0;
        transform: scale(1.05);
      }
    }

    .info {
      h3 {
        font-size: 1.25rem;
        margin: 0 0 0.25rem;
      }

      p {
        font-size: 0.875rem;
        color: #666;
        margin: 0;
      }
    }
  }

  .messages {
    flex: 1;
    overflow-y: auto;
    padding: 2rem;
  }

  .input-area {
    flex-shrink: 0;
    padding: 1.5rem 2rem;
    background: rgba(255, 255, 255, 0.98);
    border-top: 1px solid rgba(0, 0, 0, 0.05);
    display: flex;
    gap: 1rem;
    align-items: flex-end;

    textarea {
      flex: 1;
      resize: none;
      border: 1px solid rgba(0, 0, 0, 0.1);
      background: #fff;
      border-radius: 0.75rem;
      padding: 1rem;
      color: inherit;
      font-family: inherit;
      font-size: 1rem;
      line-height: 1.5;
      max-height: 150px;

      &:focus {
        outline: none;
        border-color: #333;
        box-shadow: 0 0 0 2px rgba(0, 0, 0, 0.1);
      }
    }
  }

  .send-button {
    background: #333;
    color: #fff;
    border: none;
    border-radius: 0.5rem;
    width: 2.5rem;
    height: 2.5rem;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: background-color 0.3s;

    &:hover:not(:disabled) {
      background: #000;
    }

    &:disabled {
      background: #ccc;
      cursor: not-allowed;
    }

    &.stop-button {
      background: #b42318;

      &:hover:not(:disabled) {
        background: #912018;
      }
    }
  }

  .booking-modal {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
  }

  .modal-content {
    background: #fff;
    padding: 2rem;
    border-radius: 1rem;
    max-width: 500px;
    width: 90%;
    text-align: center;

    h3 {
      font-size: 1.5rem;
      margin: 0 0 1rem;
      color: #333;
    }

    .booking-info {
      margin: 1.5rem 0;
      text-align: left;
      line-height: 1.6;
      color: #666;
    }

    button {
      padding: 0.75rem 2rem;
      background: #333;
      color: #fff;
      border: none;
      border-radius: 0.5rem;
      cursor: pointer;
      transition: background-color 0.3s;

      &:hover {
        background: #000;
      }
    }
  }
}

.dark {
  .sidebar,
  .chat-main {
    background: rgba(40, 40, 40, 0.95);
    box-shadow: 0 4px 6px rgba(0, 0, 0, 0.2);
  }

  .service-header,
  .input-area {
    background: rgba(30, 30, 30, 0.98);
    border-color: rgba(255, 255, 255, 0.05);
  }

  .service-info {
    .avatar {
      color: #fff;
      background: #444;

      &:hover {
        background: #555;
      }
    }

    .info p {
      color: #999;
    }
  }

  textarea {
    background: rgba(50, 50, 50, 0.95);
    border-color: rgba(255, 255, 255, 0.1);
    color: #fff;

    &:focus {
      border-color: #666;
      box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.1);
    }
  }

  .modal-content {
    background: #333;

    h3 {
      color: #fff;
    }

    .booking-info {
      color: #ccc;
    }

    button {
      background: #666;

      &:hover {
        background: #888;
      }
    }
  }
}

@media (max-width: 768px) {
  .customer-service {
    .chat-container {
      padding: 0;
    }

    .sidebar {
      display: none;
    }

    .chat-main {
      border-radius: 0;
    }
  }
}
</style>
