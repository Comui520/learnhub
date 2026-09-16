const API_BASE = '/api/v1'

type ApiResponse<T> = {
  code: string
  message: string
  httpStatus: number
  data: T
  timestamp: string
}

export type User = { id: number; username: string; nickname?: string }
export type TokenResponse = { token: string; tokenType: string; expirationSeconds: number }
export type KnowledgeBase = { id: number; name: string; description?: string; createdAt?: string }
export type Page<T> = { records: T[]; current: number; pages: number; total: number; size: number }
export type DocumentFile = { fileId: number; fileName: string; fileSize: number; sha256?: string; status: string; createdAt?: string }
export type DocumentTask = { id: number; fileId: number; fileName?: string; type?: string; status: string; lastError?: string; createdAt?: string }
export type StudyOption = { optionKey: string; content: string }
export type StudyQuestion = { id: number; knowledgeBaseId: number; questionType: 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE'; content: string; options: StudyOption[] }
export type QuestionDetail = { questionType?: string; content: string; analysis?: string; options: Array<StudyOption & { isCorrect: boolean }> }
export type StudyQuestionPage = { records: StudyQuestion[]; current: number; pages: number; total: number; size: number }
export type StudyAnswer = { questionId: number; selectedOptions: string[]; correct: boolean; correctOptions: string[]; analysis?: string }
export type CreditOrder = { orderNo: string; userId: number; amount: number; status: string; paidAt?: string; expireAt?: string; createdAt?: string }
export type ChatReference = { fileName: string; chunkIndex: number }

function getToken(): string | null {
  return localStorage.getItem('learnhub_token')
}

function getErrorMessage(payload: unknown, fallback: string): string {
  if (payload && typeof payload === 'object') {
    const candidate = payload as { message?: unknown; data?: { message?: unknown } }
    if (typeof candidate.message === 'string') return candidate.message
    if (typeof candidate.data?.message === 'string') return candidate.data.message
  }
  return fallback
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (!(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  const contentType = response.headers.get('content-type') || ''
  const payload = contentType.includes('application/json') ? await response.json() : await response.text()
  if (!response.ok) {
    if (response.status === 401) window.dispatchEvent(new CustomEvent('learnhub:unauthorized'))
    throw new Error(getErrorMessage(payload, `请求失败（${response.status}）`))
  }
  if (typeof payload === 'object' && payload !== null && 'data' in payload) {
    const envelope = payload as ApiResponse<T>
    if (envelope.code && envelope.code !== 'COMMON_0000') throw new Error(envelope.message || '请求未成功')
    return envelope.data
  }
  return payload as T
}

export const api = {
  login: (body: { username: string; password: string }) => request<TokenResponse>('/auth/login', { method: 'POST', body: JSON.stringify(body) }),
  register: (body: { username: string; password: string }) => request<User>('/auth/register', { method: 'POST', body: JSON.stringify(body) }),
  me: () => request<User>('/users/me'),
  listUsers: () => request<User[]>('/users'),
  knowledgeBases: (page = 1, size = 50) => request<Page<KnowledgeBase>>(`/knowledge-bases?page=${page}&size=${size}`),
  knowledgeBase: (id: number) => request<KnowledgeBase>(`/knowledge-bases/${id}`),
  createKnowledgeBase: (body: { name: string; description?: string }) => request<KnowledgeBase>('/knowledge-bases/create', { method: 'POST', body: JSON.stringify(body) }),
  updateKnowledgeBase: (id: number, body: { name: string; description?: string }) => request<KnowledgeBase>(`/knowledge-bases/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteKnowledgeBase: (id: number) => request<void>(`/knowledge-bases/${id}`, { method: 'DELETE' }),
  documents: (page = 1, size = 50) => request<Page<DocumentFile>>(`/document?page=${page}&size=${size}`),
  knowledgeBaseDocuments: (id: number, page = 1, size = 50) => request<Page<DocumentFile>>(`/knowledge-bases/${id}/file?page=${page}&size=${size}`),
  uploadDocument: (file: File) => { const body = new FormData(); body.append('file', file); return request<DocumentFile>('/document', { method: 'POST', body }) },
  deleteDocument: (id: number) => request<unknown>(`/document/${id}`, { method: 'DELETE' }),
  reparseDocument: (id: number) => request<unknown>(`/document/${id}/reparse`, { method: 'POST' }),
  shareDocument: (id: number) => request<string>(`/document/${id}/share-url`),
  bindDocuments: (knowledgeBaseId: number, documentFileIds: number[]) => request<void>('/knowledge-bases/bind-document', { method: 'POST', body: JSON.stringify({ knowledgeBaseId, documentFileIds }) }),
  unbindDocuments: (knowledgeBaseId: number, documentFileIds: number[]) => request<void>('/knowledge-bases/unbind-document', { method: 'POST', body: JSON.stringify({ knowledgeBaseId, documentFileIds }) }),
  tasks: (id: number, page = 1, size = 50, status = '') => request<Page<DocumentTask>>(`/knowledge-bases/${id}/task?page=${page}&size=${size}${status ? `&status=${encodeURIComponent(status)}` : ''}`),
  balance: () => request<number>('/credit/balance'),
  createOrder: (amount: number) => request<CreditOrder>('/credit/orders', { method: 'POST', body: JSON.stringify({ amount }) }),
  notifyPayment: (body: { orderNo: string; amount: number; tradeNo: string }) => request<void>('/credit/payments/notify', { method: 'POST', body: JSON.stringify(body) }),
  generateQuestions: (knowledgeBaseId: number, body: { count: number; topic?: string; questionType: string }) => request<StudyQuestion[]>(`/study/question/${knowledgeBaseId}/generate`, { method: 'POST', body: JSON.stringify(body) }),
  studyQuestionPage: (knowledgeBaseId: number, page = 1, size = 12, questionType = '') => request<StudyQuestionPage>(`/study/question/${knowledgeBaseId}/page`, { method: 'POST', body: JSON.stringify({ knowledgeBaseId, page, size, ...(questionType ? { questionType } : {}) }) }),
  question: (id: number) => request<StudyQuestion>(`/study/questions/${id}`),
  questionDetail: (id: number) => request<QuestionDetail>(`/study/question/${id}/detail`),
  deleteQuestion: (id: number) => request<void>(`/study/question/${id}`, { method: 'DELETE' }),
  deleteQuestions: (ids: number[]) => request<void>('/study/questions', { method: 'DELETE', body: JSON.stringify({ ids }) }),
  submitAnswer: (id: number, options: string[]) => request<StudyAnswer>(`/study/questions/${id}/attempts`, { method: 'POST', body: JSON.stringify({ options }) }),
}

function parseJsonSafe(raw: string): unknown {
  try { return JSON.parse(raw) as unknown } catch { return raw }
}

function messageFromRaw(raw: string, fallback: string): string {
  if (!raw.trim()) return fallback
  return getErrorMessage(parseJsonSafe(raw), raw.trim()) || fallback
}

type SseEvent = { event: string; data: string }
type ChatStreamHandlers = {
  onReferences: (refs: ChatReference[]) => void
  onContent: (chunk: string) => void
  onError: (message: string) => void
}

function parseSseBlock(block: string): SseEvent | null {
  const lines = block.replaceAll('\r\n', '\n').replaceAll('\r', '\n').split('\n')
  let event = 'message'
  const dataLines: string[] = []

  for (const line of lines) {
    if (!line || line.startsWith(':')) continue
    const separator = line.indexOf(':')
    const field = separator >= 0 ? line.slice(0, separator) : line
    const value = separator >= 0 ? line.slice(separator + 1).replace(/^ /, '') : ''
    if (field === 'event') event = value.trim() || 'message'
    if (field === 'data') dataLines.push(value)
  }

  return dataLines.length || event !== 'message'
    ? { event, data: dataLines.join('\n') }
    : null
}

function splitSseBlocks(raw: string, flush = false): { blocks: string[]; rest: string } {
  const blocks: string[] = []
  let rest = raw
  const separator = /(?:\r\n|\r|\n){2}/
  let match = separator.exec(rest)

  while (match) {
    blocks.push(rest.slice(0, match.index))
    rest = rest.slice(match.index + match[0].length)
    match = separator.exec(rest)
  }

  if (flush && rest) {
    blocks.push(rest)
    rest = ''
  }
  return { blocks, rest }
}

function parseSseText(raw: string): SseEvent[] {
  return splitSseBlocks(raw, true).blocks
    .map(parseSseBlock)
    .filter((event): event is SseEvent => Boolean(event))
}

function handleSseEvent(event: SseEvent, handlers: ChatStreamHandlers): 'continue' | 'error' {
  if (event.event === 'references') {
    try {
      const parsed = JSON.parse(event.data) as unknown
      handlers.onReferences(Array.isArray(parsed) ? parsed as ChatReference[] : [])
    } catch {
      handlers.onReferences([])
    }
    return 'continue'
  }

  if (event.event === 'content' || event.event === 'message') {
    handlers.onContent(event.data)
    return 'continue'
  }

  if (event.event === 'error') {
    handlers.onError(messageFromRaw(event.data, '模型调用失败，请稍后重试'))
    return 'error'
  }

  return 'continue'
}

function emitUnauthorized(status: number): void {
  if (status === 401) window.dispatchEvent(new CustomEvent('learnhub:unauthorized'))
}

export async function streamChat(
  knowledgeBaseId: number,
  question: string,
  handlers: ChatStreamHandlers,
): Promise<void> {
  const token = getToken()
  const response = await fetch(`${API_BASE}/knowledge-bases/${knowledgeBaseId}/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ question }),
  })
  const contentType = (response.headers.get('content-type') || '').toLowerCase()
  emitUnauthorized(response.status)

  if (!contentType.includes('text/event-stream')) {
    const raw = await response.text()
    if (!response.ok) throw new Error(messageFromRaw(raw, `对话失败（${response.status}）`))

    const payload = parseJsonSafe(raw)
    if (payload && typeof payload === 'object' && 'code' in payload) {
      const envelope = payload as Partial<ApiResponse<unknown>>
      if (envelope.code && envelope.code !== 'COMMON_0000') {
        throw new Error(envelope.message || '对话请求未成功')
      }
    }
    throw new Error('对话接口未返回有效的 SSE 事件流')
  }

  if (!response.ok) {
    const raw = await response.text()
    const errorEvent = parseSseText(raw).find((event) => event.event === 'error')
    throw new Error(messageFromRaw(errorEvent?.data || raw, `对话失败（${response.status}）`))
  }
  if (!response.body) throw new Error('浏览器不支持流式响应')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let eventCount = 0
  let stoppedByError = false

  try {
    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const parsed = splitSseBlocks(buffer, done)
      buffer = parsed.rest

      for (const block of parsed.blocks) {
        const event = parseSseBlock(block)
        if (!event) continue
        eventCount += 1
        if (handleSseEvent(event, handlers) === 'error') {
          stoppedByError = true
          try { await reader.cancel() } catch { /* the server may have closed the stream already */ }
          break
        }
      }

      if (stoppedByError || done) break
    }
  } catch (error) {
    try { await reader.cancel() } catch { /* connection is already closed */ }
    const detail = error instanceof Error && error.message ? `：${error.message}` : ''
    throw new Error(`流式连接中断，请稍后重试${detail}`)
  }

  if (!stoppedByError && eventCount === 0) {
    throw new Error('AI 服务未返回有效内容，请稍后重试')
  }
}
