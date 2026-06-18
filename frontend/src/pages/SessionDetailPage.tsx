import { useEffect, useState } from 'react';
import { api } from '../api/http';
import type { HookEvent, SessionDetail } from '../types/domain';
import { fmtDuration, fmtNum, fmtTime } from '../utils/format';

interface Props { id: number; onBack: () => void; }

interface ConversationTurn {
  id: string;
  index: number;
  prompt: string;
  startedAt?: string;
  events: HookEvent[];
  inputTokens: number;
  outputTokens: number;
  cachedTokens: number;
  totalTokens: number;
  cost: number;
  models: string[];
  toolCount: number;
  durationMs: number;
}

interface UsageEvent {
  line?: number;
  ts?: number;
  durationMs?: number;
  name?: string;
  status?: string;
  model?: string;
  debugName?: string;
  inputTokens?: number;
  outputTokens?: number;
  cachedTokens?: number;
  totalTokens?: number;
  ttftMs?: number;
  maxOutputTokens?: number;
  copilotUsageAiu?: number;
  responseId?: string;
}

type TraceKind = 'llm' | 'tool' | 'subagent' | 'user' | 'hook';

interface TraceItem {
  id: string;
  kind: TraceKind;
  title: string;
  subtitle: string;
  event: HookEvent;
  usage?: UsageEvent;
  raw?: unknown;
}

function json(value: unknown) {
  if (value == null) return '-';
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}

function extractReadableContent(value: unknown, depth = 0): string {
  if (value == null || depth > 5) return '';
  if (typeof value === 'string') return value;
  if (typeof value !== 'object') return '';
  const obj = value as Record<string, unknown>;
  for (const key of ['last_assistant_message', 'delta', 'displayContent', 'content', 'text', 'message', 'response', 'output', 'completion', 'result', 'tool_response']) {
    const v = obj[key];
    if (typeof v === 'string' && v.trim()) return v;
  }
  for (const v of Object.values(obj)) {
    if (Array.isArray(v)) {
      const hit = v.map(item => extractReadableContent(item, depth + 1)).find(Boolean);
      if (hit) return hit;
    } else if (typeof v === 'object') {
      const hit = extractReadableContent(v, depth + 1);
      if (hit) return hit;
    }
  }
  return '';
}

function eventTitle(event: HookEvent, index: number) {
  if (event.tool) return `#${index + 1} ${event.type} · ${event.tool}`;
  if (event.model) return `#${index + 1} ${event.type} · ${event.model}`;
  return `#${index + 1} ${event.type}`;
}

function traceTitle(item: TraceItem, index: number) {
  return `#${index + 1} ${item.title}`;
}

function eventTone(type: string) {
  if (type.includes('Failure') || type.includes('error')) return 'bad';
  if (type.includes('Tool')) return 'ok';
  if (type.includes('Prompt')) return 'warn';
  return 'muted';
}

function traceTone(kind: TraceKind, eventType: string) {
  if (eventType.includes('Failure') || eventType.includes('error')) return 'bad';
  if (kind === 'llm') return 'llm';
  if (kind === 'tool') return 'ok';
  if (kind === 'subagent') return 'subagent';
  if (kind === 'user') return 'warn';
  return 'muted';
}

function isPromptEvent(event: HookEvent) {
  return event.type.toLowerCase().includes('prompt');
}

function num(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function formatCost(value?: number) {
  if (value == null || !Number.isFinite(value) || value <= 0) return '-';
  return value < 1 ? value.toFixed(6) : value.toFixed(2);
}

function formatAiu(value?: number) {
  if (value == null || !Number.isFinite(value) || value <= 0) return '-';
  return value < 1 ? value.toFixed(4) : value.toFixed(2);
}

function usageEvents(event: HookEvent): UsageEvent[] {
  const direct = event.modelUsageEvents;
  if (Array.isArray(direct)) return direct as UsageEvent[];
  const raw = event.raw as Record<string, unknown> | undefined;
  const fromRaw = raw?.model_usage_events;
  return Array.isArray(fromRaw) ? fromRaw as UsageEvent[] : [];
}

function eventUsage(event: HookEvent) {
  const llms = usageEvents(event);
  if (!llms.length) {
    return {
      inputTokens: num(event.inputTokens),
      outputTokens: num(event.outputTokens),
      cachedTokens: num(event.cachedTokens),
      totalTokens: num(event.totalTokens),
      cost: num(event.copilotUsageAiu ?? event.cost),
      durationMs: num(event.durationMs),
      models: event.model ? [event.model] : [],
    };
  }
  const inputTokens = llms.reduce((sum, item) => sum + num(item.inputTokens), 0);
  const outputTokens = llms.reduce((sum, item) => sum + num(item.outputTokens), 0);
  const cachedTokens = llms.reduce((sum, item) => sum + num(item.cachedTokens), 0);
  const durationMs = llms.reduce((sum, item) => sum + num(item.durationMs), 0);
  const cost = llms.reduce((sum, item) => sum + num(item.copilotUsageAiu), 0);
  const models = Array.from(new Set(llms.map(item => item.model).filter(Boolean))) as string[];
  return {
    inputTokens,
    outputTokens,
    cachedTokens,
    totalTokens: inputTokens + outputTokens,
    cost,
    durationMs,
    models,
  };
}

function buildTraceItems(events: HookEvent[]): TraceItem[] {
  const items: TraceItem[] = [];
  for (const event of events) {
    const lowerType = event.type.toLowerCase();
    if (isPromptEvent(event)) {
      items.push({
        id: `user-${event.id}`,
        kind: 'user',
        title: 'User Prompt',
        subtitle: `${fmtTime(event.time)} · ${event.prompt || '用户输入'}`,
        event,
        raw: event.raw,
      });
    }

    usageEvents(event).forEach((usage, index) => {
      const total = usage.totalTokens ?? (num(usage.inputTokens) + num(usage.outputTokens));
      items.push({
        id: `llm-${event.id}-${index}`,
        kind: 'llm',
        title: `LLM · ${usage.model || event.model || 'model'}`,
        subtitle: `${fmtDuration(usage.durationMs)} · 输入 ${fmtNum(usage.inputTokens)} / 输出 ${fmtNum(usage.outputTokens)} / 缓存 ${fmtNum(usage.cachedTokens)} / 总计 ${fmtNum(total)} · AIC ${formatAiu(usage.copilotUsageAiu)}`,
        event,
        usage: { ...usage, totalTokens: total },
        raw: usage,
      });
    });

    if (event.tool || lowerType.includes('tool')) {
      items.push({
        id: `tool-${event.id}`,
        kind: 'tool',
        title: `Tool · ${event.tool || event.type}`,
        subtitle: `${fmtTime(event.time)} · ${event.durationMs != null ? fmtDuration(event.durationMs) : 'instant'}`,
        event,
        raw: event.raw,
      });
    } else if (lowerType.includes('subagent')) {
      items.push({
        id: `subagent-${event.id}`,
        kind: 'subagent',
        title: `Subagent · ${event.type}`,
        subtitle: `${fmtTime(event.time)} · ${event.durationMs != null ? fmtDuration(event.durationMs) : 'instant'}`,
        event,
        raw: event.raw,
      });
    } else if (!isPromptEvent(event) && !usageEvents(event).length && !['agentstop', 'sessionstart', 'sessionend'].includes(lowerType)) {
      items.push({
        id: `hook-${event.id}`,
        kind: 'hook',
        title: `Hook · ${event.type}`,
        subtitle: `${fmtTime(event.time)} · ${event.durationMs != null ? fmtDuration(event.durationMs) : 'instant'}`,
        event,
        raw: event.raw,
      });
    }
  }
  return items;
}

function buildTurns(events: HookEvent[]): ConversationTurn[] {
  const turns: ConversationTurn[] = [];
  let current: ConversationTurn | undefined;

  const makeTurn = (event: HookEvent, index: number): ConversationTurn => ({
    id: `turn-${index}-${event.id}`,
    index,
    prompt: event.prompt || extractReadableContent(event.raw) || `第 ${index + 1} 轮对话`,
    startedAt: event.time,
    events: [],
    inputTokens: 0,
    outputTokens: 0,
    cachedTokens: 0,
    totalTokens: 0,
    cost: 0,
    models: [],
    toolCount: 0,
    durationMs: 0,
  });

  for (const event of events) {
    if (isPromptEvent(event) || !current) {
      current = makeTurn(event, turns.length);
      turns.push(current);
    }
    current.events.push(event);
    const usage = eventUsage(event);
    current.inputTokens += usage.inputTokens;
    current.outputTokens += usage.outputTokens;
    current.cachedTokens += usage.cachedTokens;
    current.totalTokens += usage.totalTokens;
    current.cost += usage.cost;
    current.durationMs += usage.durationMs;
    if (event.tool) current.toolCount += 1;
    for (const model of usage.models) {
      if (!current.models.includes(model)) current.models.push(model);
    }
  }
  return turns;
}

export function SessionDetailPage({ id, onBack }: Props) {
  const [detail, setDetail] = useState<SessionDetail>();
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null);
  const [selectedTurnId, setSelectedTurnId] = useState<string | null>(null);
  const [regenerating, setRegenerating] = useState(false);
  const [showAllEvents, setShowAllEvents] = useState(false);
  const load = () => api.session(id, { all: showAllEvents }).then(setDetail);
  useEffect(() => { load(); }, [id, showAllEvents]);
  if (!detail) return <div className="card">加载中...</div>;
  const { session: s, summary, events } = detail;
  const turns = buildTurns(events);
  const activeTurn = turns.find(t => t.id === selectedTurnId) || turns[0];
  const visibleEvents = activeTurn?.events || events;
  const traceItems = buildTraceItems(visibleEvents);
  const idx = traceItems.findIndex(item => item.id === selectedTraceId);
  const selectedIndex = traceItems.length ? (idx >= 0 ? idx : 0) : -1;
  const selectedTrace = selectedIndex >= 0 ? traceItems[selectedIndex] : undefined;
  const selected = selectedTrace?.event;
  const selectedUsage = selectedTrace?.usage;
  const readableContent = selectedTrace ? extractReadableContent(selectedTrace.raw ?? selectedTrace.event.raw) : '';
  const toolEvents = events.filter(e => e.tool).length;
  const totalDuration = turns.reduce((sum, turn) => sum + turn.durationMs, 0);
  const totalInputTokens = turns.reduce((sum, turn) => sum + turn.inputTokens, 0);
  const totalOutputTokens = turns.reduce((sum, turn) => sum + turn.outputTokens, 0);
  const totalCachedTokens = turns.reduce((sum, turn) => sum + turn.cachedTokens, 0);
  const totalTokens = totalInputTokens + totalOutputTokens;
  const totalCost = turns.reduce((sum, turn) => sum + turn.cost, 0);
  const modelNames = Array.from(new Set(turns.flatMap(turn => turn.models)));
  const regenerateSummary = async () => {
    setRegenerating(true);
    try {
      await api.regenerateSummary(id);
      await load();
    } catch (error) {
      console.error(error);
      alert(error instanceof Error ? error.message : '重新生成摘要失败，请查看服务端日志。');
    } finally {
      setRegenerating(false);
    }
  };

  return <div className="session-detail-page">
    {regenerating && <div className="summary-loading-mask" role="status" aria-live="polite">
      <div className="summary-loading-card">
        <span className="summary-spinner"></span>
        <b>正在重新生成摘要并写入向量</b>
        <small>正在调用默认模型配置，完成后页面会自动刷新，请稍候...</small>
      </div>
    </div>}
    <div className="card session-hero">
      <div className="card-head"><span>{summary?.title || `会话 ${s.sessionId}`}</span><span className="card-actions"><button className="btn ghost" onClick={() => setShowAllEvents(v => !v)}>{showAllEvents ? '仅看最近一周事件' : '显示全部事件'}</button><button className="btn" disabled={regenerating} onClick={regenerateSummary}>{regenerating ? '生成中...' : '重新生成摘要并写入向量'}</button><button className="btn ghost" disabled={regenerating} onClick={onBack}>返回列表</button></span></div>
      {!showAllEvents && <div className="notice">当前默认仅加载最近 7 天事件，可点击“显示全部事件”查看完整历史。</div>}
      <div className="session-question"><div className="card-sub">本次会话问题</div><div>{s.initialPrompt || '未捕获到初始 Prompt。'}</div></div>
      <div className="detail-stat-grid"><div><b>{turns.length}</b><span>对话轮次</span></div><div><b>{fmtDuration(totalDuration || s.durationMs)}</b><span>总耗时</span></div><div><b>{events.length}</b><span>Hook 事件</span></div><div><b>{toolEvents}</b><span>工具调用</span></div><div><b>{fmtNum(totalInputTokens)}</b><span>输入 Token</span></div><div><b>{fmtNum(totalOutputTokens)}</b><span>输出 Token</span></div><div><b>{fmtNum(totalCachedTokens)}</b><span>缓存 Token</span></div><div><b>{fmtNum(totalTokens)}</b><span>Token 总量</span></div><div><b>{formatAiu(totalCost)}</b><span>Copilot AIC</span></div><div><b>{modelNames.join(', ') || s.model || '-'}</b><span>模型</span></div></div>
      <div className="kv compact"><div>SessionId</div><div>{s.sessionId}</div><div>用户</div><div>{s.userId || '-'}</div><div>开始 / 结束</div><div>{fmtTime(s.startedAt)} → {fmtTime(s.endedAt)}</div><div>CWD</div><div>{s.cwd || '-'}</div></div>
    </div>

    {summary && <div className="card summary-collapsed"><div className="card-head">内容整理结果</div><div>{(summary.tags || []).map(t => <span className="tag" key={t}>{t}</span>)}</div><p>{summary.summary}</p></div>}

    <div className="trace-layout">
      <div className="card conversation-panel">
        <div className="card-head"><span>交互对话</span><span className="tag">{turns.length} 轮</span></div>
        <div className="conversation-list">
          {turns.map(turn => <button key={turn.id} className={`conversation-row ${activeTurn?.id === turn.id ? 'active' : ''}`} onClick={() => { setSelectedTurnId(turn.id); setSelectedTraceId(null); }}>
            <b>#{turn.index + 1} {turn.prompt}</b>
            <small>{fmtTime(turn.startedAt)} · {turn.events.length} 事件 · {turn.toolCount} 工具</small>
            <span className="usage-line">输入 {fmtNum(turn.inputTokens)} · 输出 {fmtNum(turn.outputTokens)} · 缓存 {fmtNum(turn.cachedTokens)} · AIC {formatAiu(turn.cost)} · {turn.models[0] || '-'}</span>
          </button>)}
        </div>
      </div>

      <div className="card trace-panel">
        <div className="card-head"><span>Trace 调用链</span><span className="tag">{traceItems.length} nodes</span></div>
        <div className="trace-list">
          {traceItems.map((item, index) => <button key={item.id} className={`trace-row ${selectedTrace?.id === item.id ? 'active' : ''}`} onClick={() => setSelectedTraceId(item.id)}>
            <span className={`trace-dot ${traceTone(item.kind, item.event.type)}`}></span>
            <span className="trace-main"><b>{traceTitle(item, index)}</b><small>{item.subtitle}</small></span>
          </button>)}
        </div>
      </div>

      <div className="card event-panel">
        <div className="card-head"><span>{selectedTrace ? traceTitle(selectedTrace, selectedIndex) : '调用详情'}</span><span className="card-actions"><button className="btn ghost" disabled={selectedIndex <= 0} onClick={() => setSelectedTraceId(traceItems[selectedIndex - 1].id)}>上一条</button><button className="btn ghost" disabled={selectedIndex < 0 || selectedIndex >= traceItems.length - 1} onClick={() => setSelectedTraceId(traceItems[selectedIndex + 1].id)}>下一条</button></span></div>
        {selectedTrace && selected ? <div className="event-detail">
          {selectedUsage ? <div className="kv compact"><div>节点类型</div><div>LLM</div><div>模型</div><div>{selectedUsage.model || selected.model || '-'}</div><div>耗时</div><div>{selectedUsage.durationMs != null ? fmtDuration(selectedUsage.durationMs) : '-'}</div><div>首 Token</div><div>{selectedUsage.ttftMs != null ? fmtDuration(selectedUsage.ttftMs) : '-'}</div><div>Token</div><div>输入 {fmtNum(selectedUsage.inputTokens)} / 输出 {fmtNum(selectedUsage.outputTokens)} / 缓存 {fmtNum(selectedUsage.cachedTokens)} / 总计 {fmtNum(selectedUsage.totalTokens)}</div><div>Copilot AIC</div><div>{formatAiu(selectedUsage.copilotUsageAiu)}</div><div>ResponseId</div><div className="mono breakable">{selectedUsage.responseId || '-'}</div><div>关联 Hook</div><div>#{selected.id} {selected.type}</div></div>
            : <div className="kv compact"><div>节点类型</div><div>{selectedTrace.kind}</div><div>事件ID</div><div>{selected.id}</div><div>序号</div><div>{selected.seq || '-'}</div><div>事件类型</div><div><span className={`status-${eventTone(selected.type)}`}>{selected.type}</span></div><div>SessionId</div><div className="mono breakable">{selected.sessionId || s.sessionId}</div><div>Hook 时间</div><div>{fmtTime(selected.time)}</div><div>入库时间</div><div>{fmtTime(selected.receivedAt || selected.createdAt)}</div><div>CWD</div><div className="mono breakable">{selected.cwd || s.cwd || '-'}</div><div>工具</div><div>{selected.tool || '-'}</div><div>耗时</div><div>{selected.durationMs != null ? fmtDuration(selected.durationMs) : 'instant'}</div></div>}
          {selected.prompt && <section><h4>Prompt</h4><pre>{selected.prompt}</pre></section>}
          {!selected.prompt && readableContent && <section><h4>模型/事件内容</h4><pre>{readableContent}</pre></section>}
          {selected.toolArgs != null && <section><h4>Tool Args</h4><pre>{json(selected.toolArgs)}</pre></section>}
          {selected.toolResult != null && <section><h4>Tool Result</h4><pre>{json(selected.toolResult)}</pre></section>}
          {selectedUsage != null && <section><h4>LLM Raw</h4><pre>{json(selectedUsage)}</pre></section>}
          {selected.modelUsageEvents != null && !selectedUsage && <section><h4>模型调用明细</h4><pre>{json(selected.modelUsageEvents)}</pre></section>}
          {selected.error && <section><h4>错误信息</h4><pre className="status-bad">{selected.error}</pre></section>}
          <details className="raw-json-block"><summary>Raw JSON（完整原始事件，点击展开）</summary><pre>{json(selected.raw)}</pre></details>
        </div> : <div className="empty-state">暂无事件。</div>}
      </div>
    </div>
  </div>;
}
