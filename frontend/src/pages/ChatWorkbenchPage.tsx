import { useEffect, useMemo, useState } from 'react';
import { api } from '../api/http';
import type { ChatCompletionResult, ChatModelOption, ChatOptionsPayload, ChatToolCall, McpToolOption } from '../types/domain';

interface UiMessage {
  role: 'user' | 'assistant';
  content: string;
  model?: string;
  usage?: ChatCompletionResult['usage'];
  toolCalls?: ChatToolCall[];
}

export function ChatWorkbenchPage() {
  const [options, setOptions] = useState<ChatOptionsPayload>();
  const [messages, setMessages] = useState<UiMessage[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [modelConfigId, setModelConfigId] = useState<number | ''>('');
  const [systemPrompt, setSystemPrompt] = useState('');
  const [temperature, setTemperature] = useState(0.2);
  const [topP, setTopP] = useState(1);
  const [maxTokens, setMaxTokens] = useState(1200);
  const [useAllMcp, setUseAllMcp] = useState(true);
  const [selectedTools, setSelectedTools] = useState<string[]>([]);

  useEffect(() => {
    api.chatOptions().then(data => {
      setOptions(data);
      setModelConfigId(data.defaultModelId ?? '');
      setSystemPrompt(data.defaultSystemPrompt || '');
      setSelectedTools(data.mcpTools.map(t => t.name));
    });
  }, []);

  const tools = options?.mcpTools || [];
  const models = options?.models || [];
  const activeModel = useMemo(() => models.find(m => m.id === modelConfigId), [models, modelConfigId]);

  function toggleTool(name: string) {
    setSelectedTools(list => list.includes(name) ? list.filter(item => item !== name) : [...list, name]);
  }

  async function send() {
    const content = input.trim();
    if (!content || sending) return;
    const nextMessages: UiMessage[] = [...messages, { role: 'user', content }];
    setMessages(nextMessages);
    setInput('');
    setSending(true);
    try {
      const result = await api.chatComplete({
        modelConfigId: modelConfigId === '' ? null : modelConfigId,
        systemPrompt,
        temperature,
        topP,
        maxTokens,
        useAllMcp,
        toolNames: useAllMcp ? [] : selectedTools,
        messages: nextMessages.map(m => ({ role: m.role, content: m.content })),
      });
      setMessages([...nextMessages, {
        role: 'assistant',
        content: result.content || '模型没有返回内容。',
        model: result.model,
        usage: result.usage,
        toolCalls: result.toolCalls,
      }]);
    } catch (error) {
      const message = error instanceof Error ? error.message : '对话调用失败';
      setMessages([...nextMessages, { role: 'assistant', content: `调用失败：${message}` }]);
    } finally {
      setSending(false);
    }
  }

  return <div className="chat-workbench">
    <div className="chat-sidebar card">
      <div className="card-head">对话参数</div>
      <label>使用模型
        <select className="input" value={String(modelConfigId)} onChange={e => setModelConfigId(e.target.value ? Number(e.target.value) : '')}>
          <option value="">应用默认模型（{options?.fallbackModel || '未配置'}）</option>
          {models.map(model => <option key={model.id} value={model.id}>{model.name} · {model.chatModel}{model.defaultConfig ? '（默认）' : ''}</option>)}
        </select>
      </label>
      <div className="chat-meta-box">
        <b>{activeModel?.name || '应用默认模型'}</b>
        <small>{activeModel ? `${activeModel.provider} / ${activeModel.chatModel}` : `回退到 application.yml 的 ${options?.fallbackModel || '默认模型'}`}</small>
      </div>
      <label>System Prompt
        <textarea className="input chat-system-prompt" rows={5} value={systemPrompt} onChange={e => setSystemPrompt(e.target.value)} />
      </label>
      <div className="chat-slider-grid">
        <label>Temperature
          <input className="input" type="number" min={0} max={2} step={0.1} value={temperature} onChange={e => setTemperature(Number(e.target.value || 0.2))} />
        </label>
        <label>Top P
          <input className="input" type="number" min={0} max={1} step={0.05} value={topP} onChange={e => setTopP(Number(e.target.value || 1))} />
        </label>
        <label>Max Tokens
          <input className="input" type="number" min={128} max={8192} step={64} value={maxTokens} onChange={e => setMaxTokens(Number(e.target.value || 1200))} />
        </label>
      </div>
      <div className="mcp-tool-card">
        <div className="row between"><b>MCP 工具</b><label className="check-inline"><input type="checkbox" checked={useAllMcp} onChange={e => setUseAllMcp(e.target.checked)} /> 默认使用全部</label></div>
        <div className="tool-check-list">
          {tools.map(tool => <label key={tool.name} className={`tool-check ${selectedTools.includes(tool.name) ? 'active' : ''}`}>
            <input type="checkbox" checked={useAllMcp || selectedTools.includes(tool.name)} disabled={useAllMcp} onChange={() => toggleTool(tool.name)} />
            <span>
              <b>{tool.name}</b>
              <small>{tool.description}</small>
            </span>
          </label>)}
        </div>
      </div>
    </div>
    <div className="chat-main card">
      <div className="card-head">内容整理对话工作台</div>
      <div className="chat-messages">
        {messages.length === 0 && <div className="chat-empty"><b>开始一段新对话</b><p className="help-text">这里可以直接围绕会话总结、问题排查、语义检索和 MCP 工具调用进行问答。默认开启所有 MCP，也可以只勾选部分工具。</p></div>}
        {messages.map((message, index) => <div key={index} className={`chat-bubble ${message.role}`}>
          <div className="chat-role">{message.role === 'user' ? '你' : '助手'}</div>
          <div className="chat-content">{message.content}</div>
          {(message.model || message.usage || (message.toolCalls && message.toolCalls.length > 0)) && <div className="chat-bubble-meta">
            {message.model && <span>模型：{message.model}</span>}
            {message.usage?.totalTokens != null && <span>Tokens：{message.usage.inputTokens || 0} / {message.usage.outputTokens || 0} / {message.usage.totalTokens}</span>}
            {message.toolCalls && message.toolCalls.length > 0 && <span>工具：{message.toolCalls.map(t => t.name).join(', ')}</span>}
          </div>}
        </div>)}
      </div>
      <div className="chat-composer">
        <textarea className="input chat-input" rows={3} placeholder="输入你的问题，例如：请结合最近会话和检索结果，整理这次修复的关键步骤。" value={input} onChange={e => setInput(e.target.value)} />
        <div className="row between">
          <small className="help-text">默认使用全部 MCP；如果关闭“默认使用全部”，则只会使用你勾选的工具。</small>
          <div className="row">
            <button className="btn ghost" onClick={() => setMessages([])} disabled={sending}>清空对话</button>
            <button className="btn" onClick={() => send()} disabled={sending || !input.trim()}>{sending ? '调用中...' : '发送'}</button>
          </div>
        </div>
      </div>
    </div>
  </div>;
}