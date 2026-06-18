#!/usr/bin/env node
/*
 * Local hook forwarder for copilot-hooks-web.
 *
 * Why this exists:
 * - Stop/UserPromptSubmit/PreToolUse payloads are lifecycle snapshots and often do not
 *   contain the final assistant message, detailed tool trace, token usage, or stop reason.
 * - The payload usually contains transcript_path, which is only readable on this machine.
 * - This script enriches the hook payload locally before forwarding it to the web service.
 */
const fs = require('fs');
const os = require('os');
const http = require('http');
const https = require('https');
const { URL } = require('url');

const DEFAULT_TIMEOUT_MS = 10_000;
const MAX_TRANSCRIPT_BYTES = 2 * 1024 * 1024;
const MAX_DEBUG_LOG_BYTES = 8 * 1024 * 1024;
const MAX_RECENT_RECORDS = 80;
const MAX_RECENT_MESSAGES = 12;
const MAX_RECENT_TOOLS = 30;
const MAX_RECENT_USAGE_EVENTS = 30;
const MAX_TEXT = 8_000;
const MAX_ARG_TEXT = 2_000;
const DEFAULT_USAGE_WAIT_MS = 2_500;

/*
 * ========================= 中文维护说明（核心） =========================
 * 本脚本的目标：
 * 1) 接收 Hook stdin 原始 payload；
 * 2) 基于 payload 中的 transcript_path/session_id，在本机补全 transcript 与 debug-log 信息；
 * 3) 提取并提升模型用量字段（token/缓存token/耗时/AIC/模型等）；
 * 4) 最终转发到 copilot-hooks-web 后端。
 *
 * 一、常见 Hook 事件与字段（不同客户端字段名可能略有差异）
 * ---------------------------------------------------------------------
 * 1) UserPromptSubmit
 *    - 常见字段：prompt / userPrompt / user_prompt
 *    - 作用：标识“本轮用户输入”，是后续匹配 main.jsonl 用户窗口的重要线索。
 *
 * 2) PreToolUse / PostToolUse / PostToolUseFailure / PostToolBatch
 *    - 常见字段：toolName/tool_name, toolArgs/tool_args/tool_input, toolResult/tool_result/tool_response
 *    - 作用：补全工具调用链，不直接决定 token 统计。
 *
 * 3) MessageDisplay
 *    - 常见字段：message / content / delta
 *    - 作用：更接近最终展示内容，常用于阶段性补全与展示。
 *
 * 4) Stop / SubagentStop / SessionEnd
 *    - 作用：通常最接近“本轮完成”或“会话收束”的时机。
 *    - 本脚本会在这些事件上提升 usage 字段到顶层（promoteUsageFields）。
 *
 * 二、会话与文件关联关系（如何定位 transcript 与 main.jsonl）
 * ---------------------------------------------------------------------
 * 1) 会话主键：payload.session_id（或 sessionId）
 * 2) transcript 路径：payload.transcript_path（或 transcriptPath）
 * 3) debug log 路径推导：
 *    - 先找 payload.debug_log_path/debugLogPath（显式提供优先）
 *    - 否则通过 transcript_path 推导：
 *      .../transcripts/<session>.jsonl  ->  .../debug-logs/<session_id>/main.jsonl
 *
 * 三、如何把“当前 Hook”对齐到“当前对话轮次”的 token
 * ---------------------------------------------------------------------
 * 1) 获取当前 Prompt：
 *    prompt/userPrompt/user_prompt/transcript_last_user_message/ transcript.last_user_message
 * 2) 在 main.jsonl 中寻找 type=user_message 且 content 与当前 Prompt 匹配的锚点。
 * 3) 只提取该 user_message 到“下一个 user_message 之前”的 llm_request。
 * 4) 绝不直接用“最近一条 llm_request”作为当前轮结果（避免串轮）。
 *
 * 四、token/用量字段来源（最终写入 enriched payload）
 * ---------------------------------------------------------------------
 * 来源：main.jsonl 中 llm_request.attrs
 *   attrs.inputTokens         -> enriched.inputTokens
 *   attrs.outputTokens        -> enriched.outputTokens
 *   attrs.cachedTokens        -> enriched.cachedTokens
 *   input+output              -> enriched.totalTokens
 *   record.dur                -> enriched.durationMs
 *   attrs.ttft                -> enriched.ttftMs
 *   attrs.copilotUsageNanoAiu -> enriched.copilotUsageNanoAiu
 *   nanoAiu / 1e9             -> enriched.copilotUsageAiu
 *   attrs.model               -> enriched.model / enriched.modelName
 *
 * 说明：Hook 原始 payload 经常不包含上述完整字段，因此必须在本机补全。
 * ====================================================================
 */

/*
 * Usage field source of truth (important for maintainers)
 * -------------------------------------------------------
 * The hook payload itself often does NOT include complete usage/cost/final response details.
 * We enrich locally from Copilot debug logs (main.jsonl), primarily llm_request records:
 *
 *   attrs.inputTokens         -> enriched.inputTokens
 *   attrs.outputTokens        -> enriched.outputTokens
 *   attrs.cachedTokens        -> enriched.cachedTokens
 *   input + output            -> enriched.totalTokens
 *   dur                       -> enriched.durationMs
 *   attrs.ttft                -> enriched.ttftMs
 *   attrs.copilotUsageNanoAiu -> enriched.copilotUsageNanoAiu
 *   nanoAiu / 1e9             -> enriched.copilotUsageAiu
 *   attrs.model               -> enriched.model / enriched.modelName
 *
 * Why local enrichment is required:
 * - transcript_path/debug logs are only available on the client machine.
 * - Server-side ingest cannot read local debug logs directly.
 *
 * How we avoid token mismatches between turns:
 * - Match current prompt -> corresponding user_message in main.jsonl.
 * - Only read llm_request within that user_message window
 *   (until next user_message).
 * - Do NOT simply use the latest llm_request globally.
 */

function parseArgs(argv) {
  const out = {
    url: process.env.COPILOT_HOOKS_URL || 'http://localhost:8080/api/hooks/ingest',
    token: process.env.COPILOT_HOOKS_TOKEN || '',
    source: process.env.COPILOT_HOOKS_SOURCE || 'copilot-cli',
    timeoutMs: Number(process.env.COPILOT_HOOKS_TIMEOUT_MS || DEFAULT_TIMEOUT_MS),
    usageWaitMs: Number(process.env.COPILOT_HOOKS_USAGE_WAIT_MS || DEFAULT_USAGE_WAIT_MS),
    dryRun: false,
  };
  for (let i = 2; i < argv.length; i += 1) {
    const key = argv[i];
    const next = argv[i + 1];
    if (key === '--url' && next) { out.url = next; i += 1; }
    else if (key === '--token' && next) { out.token = next; i += 1; }
    else if (key === '--source' && next) { out.source = next; i += 1; }
    else if (key === '--timeout-ms' && next) { out.timeoutMs = Number(next); i += 1; }
    else if (key === '--usage-wait-ms' && next) { out.usageWaitMs = Number(next); i += 1; }
    else if (key === '--dry-run') { out.dryRun = true; }
  }
  return out;
}

function sleepMs(ms) {
  if (!Number.isFinite(ms) || ms <= 0) return;
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function readStdin() {
  return fs.readFileSync(0, 'utf8');
}

function truncate(value, max = MAX_TEXT) {
  if (value == null) return value;
  const s = typeof value === 'string' ? value : JSON.stringify(value);
  return s.length <= max ? s : `${s.slice(0, max)}… [+${s.length - max} chars]`;
}

function safeJsonParse(line) {
  try { return JSON.parse(line); } catch { return null; }
}

function readTranscriptTail(filePath) {
  return readFileTail(filePath, MAX_TRANSCRIPT_BYTES);
}

function readFileTail(filePath, maxBytes) {
  const stat = fs.statSync(filePath);
  const start = Math.max(0, stat.size - maxBytes);
  const fd = fs.openSync(filePath, 'r');
  try {
    const len = stat.size - start;
    const buf = Buffer.alloc(len);
    fs.readSync(fd, buf, 0, len, start);
    let text = buf.toString('utf8');
    if (start > 0) {
      const firstNewline = text.indexOf('\n');
      if (firstNewline >= 0) text = text.slice(firstNewline + 1);
    }
    return { text, fileSize: stat.size, truncated: start > 0 };
  } finally {
    fs.closeSync(fd);
  }
}

function findDebugLogPath(payload, transcriptPath) {
  const explicit = payload.debug_log_path || payload.debugLogPath;
  if (explicit) return explicit;
  const sessionId = payload.session_id || payload.sessionId;
  if (!transcriptPath || !sessionId) return null;
  // 关键路径关联：用 transcript 所在目录推导 debug-logs/<sessionId>/main.jsonl
  // 例如：.../transcripts/xxx.jsonl -> .../debug-logs/<sessionId>/main.jsonl
  const marker = `${pathSep()}transcripts${pathSep()}`;
  const idx = transcriptPath.lastIndexOf(marker);
  if (idx < 0) return null;
  return `${transcriptPath.slice(0, idx)}${pathSep()}debug-logs${pathSep()}${sessionId}${pathSep()}main.jsonl`;
}

function pathSep() {
  return process.platform === 'win32' ? '\\' : '/';
}

function asNumber(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const n = Number(value);
    return Number.isFinite(n) ? n : undefined;
  }
  return undefined;
}

function payloadTimestampMs(payload) {
  const value = payload.timestamp;
  if (typeof value === 'number' && Number.isFinite(value)) return value < 100000000000 ? value * 1000 : value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  return undefined;
}

function normalizeText(value) {
  return String(value || '').replace(/\s+/g, ' ').trim();
}

function directPrompt(payload) {
  // 不同 Hook/客户端字段名不统一，这里统一兜底抽取“当前用户提问文本”。
  return payload.prompt || payload.userPrompt || payload.user_prompt || payload.transcript_last_user_message || '';
}

function currentPromptText(payload, transcript) {
  // 如果 payload 没有 prompt，则回退到 transcript 中最近 user.message。
  return normalizeText(directPrompt(payload) || (transcript && transcript.last_user_message) || '');
}

function promptMatches(content, prompt) {
  const a = normalizeText(content);
  const b = normalizeText(prompt);
  if (!a || !b) return false;
  return a === b || a.includes(b) || b.includes(a);
}

function compactUsageRecord(record, line) {
  const attrs = record && record.attrs;
  if (!attrs) return null;
  // These raw fields come from debug-logs/<session>/main.jsonl llm_request.attrs
  // and represent the most reliable per-request usage metrics we can obtain locally.
  const inputTokens = asNumber(attrs.inputTokens);
  const outputTokens = asNumber(attrs.outputTokens);
  const cachedTokens = asNumber(attrs.cachedTokens);
  const usageNanoAiu = asNumber(attrs.copilotUsageNanoAiu);
  return {
    line,
    ts: record.ts,
    durationMs: asNumber(record.dur),
    type: record.type,
    name: record.name,
    status: record.status,
    model: attrs.model,
    debugName: attrs.debugName,
    inputTokens,
    outputTokens,
    cachedTokens,
    totalTokens: inputTokens != null && outputTokens != null ? inputTokens + outputTokens : undefined,
    ttftMs: asNumber(attrs.ttft),
    maxOutputTokens: asNumber(attrs.maxTokens),
    copilotUsageNanoAiu: usageNanoAiu,
    copilotUsageAiu: usageNanoAiu != null ? usageNanoAiu / 1_000_000_000 : undefined,
    responseId: attrs.responseId,
    userRequest: truncate(attrs.userRequest, 500),
  };
}

function isPrimaryModelUsage(usage) {
  return usage && usage.type === 'llm_request' && usage.status === 'ok'
    && usage.debugName === 'panel/editAgent'
    && (usage.inputTokens != null || usage.outputTokens != null);
}

function summarizeUsage(events) {
  if (!events.length) return null;
  // Aggregate a single "conversation turn" usage summary from selected llm_request events.
  // Selected events are computed by prompt-window matching (see readDebugUsage).
  const summary = {
    model: events[events.length - 1].model,
    debugName: events[events.length - 1].debugName,
    requestCount: events.length,
    inputTokens: 0,
    outputTokens: 0,
    cachedTokens: 0,
    totalTokens: 0,
    durationMs: 0,
    ttftMs: undefined,
    maxOutputTokens: undefined,
    copilotUsageNanoAiu: 0,
    copilotUsageAiu: 0,
    responseId: events[events.length - 1].responseId,
  };
  let hasInput = false;
  let hasOutput = false;
  let hasCached = false;
  let hasDuration = false;
  let hasAiu = false;
  for (const e of events) {
    if (e.inputTokens != null) { summary.inputTokens += e.inputTokens; hasInput = true; }
    if (e.outputTokens != null) { summary.outputTokens += e.outputTokens; hasOutput = true; }
    if (e.cachedTokens != null) { summary.cachedTokens += e.cachedTokens; hasCached = true; }
    if (e.durationMs != null) { summary.durationMs += e.durationMs; hasDuration = true; }
    if (e.copilotUsageNanoAiu != null) { summary.copilotUsageNanoAiu += e.copilotUsageNanoAiu; hasAiu = true; }
    if (summary.ttftMs == null && e.ttftMs != null) summary.ttftMs = e.ttftMs;
    if (e.maxOutputTokens != null) summary.maxOutputTokens = e.maxOutputTokens;
  }
  if (!hasInput) delete summary.inputTokens;
  if (!hasOutput) delete summary.outputTokens;
  if (!hasCached) delete summary.cachedTokens;
  if (!hasDuration) delete summary.durationMs;
  if (!hasAiu) {
    delete summary.copilotUsageNanoAiu;
    delete summary.copilotUsageAiu;
  } else {
    summary.copilotUsageAiu = summary.copilotUsageNanoAiu / 1_000_000_000;
  }
  if (summary.inputTokens != null && summary.outputTokens != null) {
    summary.totalTokens = summary.inputTokens + summary.outputTokens;
  } else {
    delete summary.totalTokens;
  }
  return summary;
}

function readDebugUsage(payload, transcriptPath, transcript) {
  const debugLogPath = findDebugLogPath(payload, transcriptPath);
  const maxTs = payloadTimestampMs(payload);
  const prompt = currentPromptText(payload, transcript);
  const meta = {
    debug_log_path: debugLogPath || null,
    max_ts: maxTs || null,
    prompt_match: prompt || null,
    debug_log_read: false,
  };
  if (!debugLogPath) return { meta };
  try {
    if (!fs.existsSync(debugLogPath)) {
      meta.error = 'debug_log_path_not_found';
      return { meta };
    }
    const { text, fileSize, truncated } = readFileTail(debugLogPath, MAX_DEBUG_LOG_BYTES);
    const all = [];
    let lastUserIndex = -1;
    let matchedUserIndex = -1;
    let matchedUserTs = null;
    text.split(/\r?\n/).filter(Boolean).forEach((line, idx) => {
      const record = safeJsonParse(line);
      if (!record) return;
      if (maxTs != null && typeof record.ts === 'number' && record.ts > maxTs + 60_000) return;
      if (record.type === 'user_message') {
        const content = record.attrs && record.attrs.content;
        const entry = { type: record.type, ts: record.ts, content };
        // Prompt-window anchor: find the user_message matching current prompt.
        // This avoids reading usage from previous/next turns.
        if (promptMatches(content, prompt)) {
          matchedUserIndex = all.length;
          matchedUserTs = record.ts;
        }
        lastUserIndex = all.length;
        all.push(entry);
        return;
      }
      if (record.type === 'llm_request') {
        const usage = compactUsageRecord(record, idx + 1);
        if (usage) all.push(usage);
      } else {
        all.push({ type: record.type, ts: record.ts });
      }
    });
    const usageEvents = all.filter(r => r.type === 'llm_request');
    const recentUsageEvents = usageEvents.filter(isPrimaryModelUsage).slice(-MAX_RECENT_USAGE_EVENTS);
    // Selection strategy:
    // 1) Prefer the matched user_message window for current prompt.
    // 2) Fallback to last user_message window if matching fails.
    // 3) If no user_message anchor exists, fallback to latest recent primary event.
    const userIndex = matchedUserIndex >= 0 ? matchedUserIndex : lastUserIndex;
    const nextUserOffset = userIndex >= 0 ? all.slice(userIndex + 1).findIndex(r => r.type === 'user_message') : -1;
    const windowEnd = nextUserOffset >= 0 ? userIndex + 1 + nextUserOffset : all.length;
    const turnUsageEvents = userIndex >= 0 ? all.slice(userIndex + 1, windowEnd).filter(isPrimaryModelUsage) : [];
    const selectedUsageEvents = userIndex >= 0 ? turnUsageEvents : (recentUsageEvents.length ? [recentUsageEvents[recentUsageEvents.length - 1]] : []);
    meta.debug_log_read = true;
    meta.file_size = fileSize;
    meta.tail_truncated = truncated;
    meta.parsed_usage_events = usageEvents.length;
    meta.matched_user = matchedUserIndex >= 0;
    meta.matched_user_ts = matchedUserTs;
    meta.selected_usage_events = selectedUsageEvents.length;
    return {
      meta,
      usage: {
        latest: selectedUsageEvents[selectedUsageEvents.length - 1] || null,
        summary: summarizeUsage(selectedUsageEvents),
        turn_events: selectedUsageEvents,
        recent_events: recentUsageEvents,
      },
    };
  } catch (err) {
    meta.error = err && err.message ? err.message : String(err);
    return { meta };
  }
}

function enrichFromDebugLog(payload, transcriptPath, transcript, args) {
  const shouldWait = shouldPromoteUsage(payload) && args && args.usageWaitMs > 0;
  if (shouldWait) sleepMs(args.usageWaitMs);
  let result = readDebugUsage(payload, transcriptPath, transcript);
  if (shouldWait && result.meta) {
    result.meta.waited_ms = args.usageWaitMs;
  }
  return result;
}

function shouldPromoteUsage(payload) {
  const event = payload.hook_event_name || payload.hookEventName || payload.event || payload.eventType || payload.event_type;
  // These events are usually closest to "final" turn state; promoting usage here improves
  // downstream UI/API consistency for model/token/cost fields.
  // 中文说明：这些事件更接近“本轮输出完成”，在这里把 usage 提升到顶层字段，
  // 可避免前端/后端只读顶层字段时看不到 token 与费用信息。
  return ['Stop', 'SubagentStop', 'MessageDisplay', 'SessionEnd'].includes(event);
}

function promoteUsageFields(enriched, usage) {
  const summary = usage && usage.summary;
  if (!summary) return;
  // Keep both rich nested usage and flattened top-level fields:
  // - model_usage/model_usage_events for detailed trace rendering.
  // - top-level fields for backward-compatible ingestion and quick dashboards.
  enriched.model_usage = summary;
  enriched.model_usage_events = usage.turn_events || [];
  if (enriched.model == null && summary.model != null) enriched.model = summary.model;
  if (enriched.modelName == null && summary.model != null) enriched.modelName = summary.model;
  if (enriched.inputTokens == null && summary.inputTokens != null) enriched.inputTokens = summary.inputTokens;
  if (enriched.outputTokens == null && summary.outputTokens != null) enriched.outputTokens = summary.outputTokens;
  if (enriched.cachedTokens == null && summary.cachedTokens != null) enriched.cachedTokens = summary.cachedTokens;
  if (enriched.totalTokens == null && summary.totalTokens != null) enriched.totalTokens = summary.totalTokens;
  if (enriched.durationMs == null && summary.durationMs != null) enriched.durationMs = summary.durationMs;
  if (enriched.ttftMs == null && summary.ttftMs != null) enriched.ttftMs = summary.ttftMs;
  if (enriched.copilotUsageNanoAiu == null && summary.copilotUsageNanoAiu != null) enriched.copilotUsageNanoAiu = summary.copilotUsageNanoAiu;
  if (enriched.copilotUsageAiu == null && summary.copilotUsageAiu != null) enriched.copilotUsageAiu = summary.copilotUsageAiu;
}

function getContent(record) {
  const data = record && record.data;
  if (!data) return '';
  if (typeof data.content === 'string') return data.content;
  if (Array.isArray(data.content)) {
    return data.content.map(part => {
      if (typeof part === 'string') return part;
      if (part && typeof part.text === 'string') return part.text;
      return '';
    }).filter(Boolean).join('\n');
  }
  if (typeof data.message === 'string') return data.message;
  if (data.message && typeof data.message.content === 'string') return data.message.content;
  if (typeof data.reasoningText === 'string') return data.reasoningText;
  return '';
}

function extractToolRequests(record) {
  const reqs = record && record.data && record.data.toolRequests;
  if (!Array.isArray(reqs)) return [];
  return reqs.map(req => ({
    toolCallId: req.toolCallId,
    name: req.name,
    type: req.type,
    arguments: truncate(req.arguments, MAX_ARG_TEXT),
  }));
}

function compactRecord(record) {
  if (!record || !record.type) return null;
  const base = {
    type: record.type,
    timestamp: record.timestamp,
    id: record.id,
    parentId: record.parentId,
  };
  const content = getContent(record);
  if (content) base.content = truncate(content);
  const toolRequests = extractToolRequests(record);
  if (toolRequests.length) base.toolRequests = toolRequests;
  const data = record.data || {};
  if (record.type === 'tool.execution_start') {
    base.toolCallId = data.toolCallId;
    base.toolName = data.toolName;
    base.arguments = truncate(data.arguments, MAX_ARG_TEXT);
  }
  if (record.type === 'tool.execution_complete') {
    base.toolCallId = data.toolCallId;
    base.success = data.success;
    if (data.error) base.error = truncate(data.error);
  }
  if (record.type === 'assistant.message' && data.reasoningText && !base.reasoningText) {
    base.reasoningText = truncate(data.reasoningText);
  }
  return base;
}

function enrichFromTranscript(payload) {
  const transcriptPath = payload.transcript_path || payload.transcriptPath;
  const meta = {
    script: 'copilot-hooks-web/.github/hooks/send-hook.js',
    version: 1,
    enriched_at: new Date().toISOString(),
    hostname: os.hostname(),
    transcript_path: transcriptPath || null,
    transcript_read: false,
  };

  if (!transcriptPath) return { meta };
  try {
    if (!fs.existsSync(transcriptPath)) {
      meta.error = 'transcript_path_not_found';
      return { meta };
    }
    const { text, fileSize, truncated } = readTranscriptTail(transcriptPath);
    const records = text.split(/\r?\n/).filter(Boolean).map(safeJsonParse).filter(Boolean);
    const compact = records.slice(-MAX_RECENT_RECORDS).map(compactRecord).filter(Boolean);
    const messages = compact.filter(r => r.type === 'user.message' || r.type === 'assistant.message').slice(-MAX_RECENT_MESSAGES);
    const tools = compact.filter(r => r.type === 'tool.execution_start' || r.type === 'tool.execution_complete').slice(-MAX_RECENT_TOOLS);
    const lastUser = [...compact].reverse().find(r => r.type === 'user.message' && r.content);
    const lastAssistant = [...compact].reverse().find(r => r.type === 'assistant.message' && r.content);
    const lastAssistantWithTools = [...compact].reverse().find(r => r.type === 'assistant.message' && (r.content || r.toolRequests));

    meta.transcript_read = true;
    meta.file_size = fileSize;
    meta.tail_truncated = truncated;
    meta.parsed_records = records.length;

    return {
      meta,
      transcript: {
        last_user_message: lastUser && lastUser.content,
        last_assistant_message: lastAssistant && lastAssistant.content,
        last_assistant_event: lastAssistantWithTools || null,
        recent_messages: messages,
        recent_tool_events: tools,
        recent_records: compact,
      },
    };
  } catch (err) {
    meta.error = err && err.message ? err.message : String(err);
    return { meta };
  }
}

function enrichPayload(payload, args) {
  const transcriptPath = payload.transcript_path || payload.transcriptPath;
  const { meta, transcript } = enrichFromTranscript(payload);
  const { meta: debugMeta, usage } = enrichFromDebugLog(payload, transcriptPath, transcript, args);
  const enriched = {
    ...payload,
    hook_forwarder: {
      ...meta,
      usage_debug: debugMeta,
    },
  };
  if (transcript) {
    enriched.transcript_enrichment = transcript;
    if (!enriched.last_assistant_message && transcript.last_assistant_message) {
      enriched.last_assistant_message = transcript.last_assistant_message;
    }
    if (!enriched.transcript_last_user_message && transcript.last_user_message) {
      enriched.transcript_last_user_message = transcript.last_user_message;
    }
  }
  if (usage && shouldPromoteUsage(payload)) {
    // model_usage_latest: last request in selected turn window
    // model_usage_recent: recent primary llm requests for troubleshooting context
    // 中文说明：
    // - model_usage_latest：当前选中“对话窗口”内最后一条 llm_request
    // - model_usage_recent：最近若干 primary llm_request（用于排障）
    enriched.model_usage_latest = usage.latest;
    enriched.model_usage_recent = usage.recent_events;
    promoteUsageFields(enriched, usage);
  }
  return enriched;
}

function sendJson({ url, token, source, timeoutMs }, payload) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    const body = JSON.stringify(payload);
    const client = target.protocol === 'https:' ? https : http;
    const req = client.request({
      method: 'POST',
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port,
      path: `${target.pathname}${target.search}`,
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
        'Authorization': token ? `Bearer ${token}` : undefined,
        'X-Source': source,
        'X-Hook-Forwarder': 'copilot-hooks-web-local-script',
      },
      timeout: Number.isFinite(timeoutMs) ? timeoutMs : DEFAULT_TIMEOUT_MS,
    }, res => {
      res.resume();
      res.on('end', () => resolve(res.statusCode));
    });
    req.on('timeout', () => {
      req.destroy(new Error('request_timeout'));
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

(async () => {
  const args = parseArgs(process.argv);
  const stdin = readStdin();
  if (!stdin.trim()) return;
  let payload;
  try {
    payload = JSON.parse(stdin);
  } catch (err) {
    // Preserve malformed input for debugging instead of dropping it silently.
    payload = {
      hook_event_name: 'MalformedHookPayload',
      session_id: `malformed-${Date.now()}`,
      timestamp: new Date().toISOString(),
      raw_stdin: truncate(stdin),
      parse_error: err && err.message ? err.message : String(err),
    };
  }
  const enriched = Array.isArray(payload) ? payload.map(item => enrichPayload(item, args)) : enrichPayload(payload, args);
  if (args.dryRun) {
    process.stdout.write(`${JSON.stringify(enriched, null, 2)}\n`);
    return;
  }
  await sendJson(args, enriched);
})().catch(err => {
  // Hooks should never break the agent flow; log to stderr for local debugging only.
  console.error(`[copilot-hooks-web] failed to forward hook: ${err && err.message ? err.message : err}`);
  process.exit(0);
});
