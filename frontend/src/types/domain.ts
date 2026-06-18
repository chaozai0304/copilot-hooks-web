export type Role = 'ADMIN' | 'USER';

export interface Me {
  userId: number;
  username: string;
  role: Role;
  authType?: string;
  displayName?: string | null;
  email?: string | null;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface HookSession {
  id: number;
  userId?: number;
  sessionId: string;
  title?: string;
  tags?: string[];
  startedAt?: string;
  endedAt?: string;
  lastEventAt?: string;
  cwd?: string;
  initialPrompt?: string;
  model?: string;
  eventCount: number;
  toolCount: number;
  errorCount: number;
  promptCount: number;
  inputTokens?: number;
  outputTokens?: number;
  cachedTokens?: number;
  totalTokens?: number;
  copilotUsageAiu?: number;
  durationMs?: number;
  endReason?: string;
}

export interface HookEvent {
  id: number;
  seq?: number;
  sessionId?: string;
  type: string;
  time?: string;
  receivedAt?: string;
  createdAt?: string;
  rawTimestamp?: string;
  transcriptPath?: string;
  cwd?: string;
  tool?: string;
  prompt?: string;
  toolArgs?: unknown;
  toolResult?: unknown;
  error?: string;
  model?: string;
  durationMs?: number;
  inputTokens?: number;
  outputTokens?: number;
  cachedTokens?: number;
  totalTokens?: number;
  ttftMs?: number;
  copilotUsageAiu?: number;
  cost?: number;
  modelUsageEvents?: unknown;
  raw?: unknown;
}

export interface SessionSummary {
  title?: string;
  summary?: string;
  highlights?: string;
  tags?: string[];
}

export interface SessionDetail {
  session: HookSession;
  summary?: SessionSummary;
  events: HookEvent[];
  eventRange?: 'recent_week' | 'all';
}

export interface ApiToken {
  id: number;
  name: string;
  prefix: string;
  expiresAt?: string;
  lastUsedAt?: string;
  revoked: boolean;
}

export interface UserAccount {
  id: number;
  username: string;
  displayName?: string;
  email?: string;
  role: Role;
  enabled: boolean;
}

export interface SearchHit {
  id: string;
  score?: number;
  content?: string;
  metadata?: Record<string, any>;
}

export interface ModelConfig {
  id: number;
  name: string;
  provider: string;
  baseUrl: string;
  apiKeyMasked?: string;
  hasApiKey?: boolean;
  chatModel: string;
  embeddingModel: string;
  embeddingDimensions: number;
  enabled: boolean;
  defaultConfig: boolean;
  updatedAt?: string;
}

export interface ChatModelOption {
  id: number;
  name: string;
  provider: string;
  chatModel: string;
  enabled: boolean;
  defaultConfig: boolean;
}

export interface McpToolOption {
  name: string;
  description: string;
  inputSchema?: string;
}

export interface ChatOptionsPayload {
  defaultModelId?: number | null;
  fallbackModel?: string;
  defaultSystemPrompt?: string;
  models: ChatModelOption[];
  mcpTools: McpToolOption[];
}

export interface ChatToolCall {
  id?: string;
  type?: string;
  name: string;
  arguments?: string;
}

export interface ChatCompletionResult {
  content: string;
  model?: string;
  usage?: {
    inputTokens?: number;
    outputTokens?: number;
    totalTokens?: number;
  };
  toolCalls?: ChatToolCall[];
}
