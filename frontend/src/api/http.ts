import type { ApiToken, ChatCompletionResult, ChatOptionsPayload, HookSession, Me, ModelConfig, PageResult, SearchHit, SessionDetail, UserAccount } from '../types/domain';

const BASIC_KEY = 'copilot-hooks.basic';

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export function getBasicAuth() {
  return localStorage.getItem(BASIC_KEY);
}

export function setBasicAuth(username: string, password: string) {
  const encoded = btoa(unescape(encodeURIComponent(`${username}:${password}`)));
  localStorage.setItem(BASIC_KEY, encoded);
}

export function clearBasicAuth() {
  localStorage.removeItem(BASIC_KEY);
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...(options.headers as Record<string, string> | undefined) };
  const basic = getBasicAuth();
  if (basic) headers.Authorization = `Basic ${basic}`;

  let body = options.body;
  if (body && typeof FormData !== 'undefined' && body instanceof FormData) {
    // Let the browser set multipart boundaries automatically.
  } else if (body && typeof body !== 'string') {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }

  const response = await fetch(path, { ...options, headers, body });
  if (!response.ok) {
    let message = response.statusText;
    try {
      const data = await response.json();
      message = data.error || data.message || message;
    } catch {}
    throw new ApiError(response.status, message);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export async function requestBlob(path: string, options: RequestInit = {}): Promise<Blob> {
  const headers: Record<string, string> = { ...(options.headers as Record<string, string> | undefined) };
  const basic = getBasicAuth();
  if (basic) headers.Authorization = `Basic ${basic}`;

  const response = await fetch(path, { ...options, headers });
  if (!response.ok) {
    let message = response.statusText;
    try {
      const data = await response.json();
      message = data.error || data.message || message;
    } catch {}
    throw new ApiError(response.status, message);
  }
  return response.blob();
}

export const api = {
  me: () => request<Me>('/api/me'),
  updateMe: (body: { displayName?: string; email?: string; currentPassword?: string; newPassword?: string }) => request<Me>('/api/me', { method: 'PUT', body: body as any }),
  sessions: (page = 0, size = 20, filters?: { month?: string; day?: string; from?: string; to?: string; userId?: number | null }) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (filters?.month) params.set('month', filters.month);
    if (filters?.day) params.set('day', filters.day);
    if (filters?.from) params.set('from', filters.from);
    if (filters?.to) params.set('to', filters.to);
    if (filters?.userId != null) params.set('userId', String(filters.userId));
    return request<PageResult<HookSession>>(`/api/sessions?${params.toString()}`);
  },
  session: (id: number | string, opts?: { all?: boolean }) => request<SessionDetail>(`/api/sessions/${id}${opts?.all ? '?all=true' : ''}`),
  regenerateSummary: (id: number | string) => request<SessionDetail>(`/api/sessions/${id}/summary`, { method: 'POST' }),
  search: (q: string, topK = 10) => request<SearchHit[]>(`/api/search?q=${encodeURIComponent(q)}&topK=${topK}`),
  tokens: () => request<ApiToken[]>('/api/tokens'),
  createToken: (body: { name: string; expiresAt: string | null }) => request<{ token: string }>('/api/tokens', { method: 'POST', body: body as any }),
  revokeToken: (id: number) => request<void>(`/api/tokens/${id}`, { method: 'DELETE' }),
  users: () => request<UserAccount[]>('/api/admin/users'),
  createUser: (body: any) => request<UserAccount>('/api/admin/users', { method: 'POST', body }),
  downloadUserImportTemplate: () => requestBlob('/api/admin/users/import/template'),
  importUsersExcel: (file: File) => {
    const body = new FormData();
    body.append('file', file);
    return request<{ created: number; skipped: number; errors: Array<{ row: number; message: string }> }>('/api/admin/users/import', { method: 'POST', body });
  },
  deleteUser: (id: number) => request<void>(`/api/admin/users/${id}`, { method: 'DELETE' }),
  enableUser: (id: number) => request<void>(`/api/admin/users/${id}/enable`, { method: 'POST' }),
  disableUser: (id: number) => request<void>(`/api/admin/users/${id}/disable`, { method: 'POST' }),
  issueUserToken: (id: number, name: string) => request<{ token: string }>(`/api/admin/users/${id}/tokens`, { method: 'POST', body: { name, expiresAt: null } as any }),
  modelConfigs: () => request<ModelConfig[]>('/api/admin/model-configs'),
  createModelConfig: (body: any) => request<ModelConfig>('/api/admin/model-configs', { method: 'POST', body }),
  updateModelConfig: (id: number, body: any) => request<ModelConfig>(`/api/admin/model-configs/${id}`, { method: 'PUT', body }),
  enableModelConfig: (id: number) => request<ModelConfig>(`/api/admin/model-configs/${id}/enable`, { method: 'POST' }),
  disableModelConfig: (id: number) => request<ModelConfig>(`/api/admin/model-configs/${id}/disable`, { method: 'POST' }),
  setDefaultModelConfig: (id: number) => request<ModelConfig>(`/api/admin/model-configs/${id}/default`, { method: 'POST' }),
  deleteModelConfig: (id: number) => request<void>(`/api/admin/model-configs/${id}`, { method: 'DELETE' }),
  chatOptions: () => request<ChatOptionsPayload>('/api/chat/options'),
  chatComplete: (body: any) => request<ChatCompletionResult>('/api/chat/completions', { method: 'POST', body }),
};
