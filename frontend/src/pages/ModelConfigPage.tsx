import { useEffect, useMemo, useState } from 'react';
import { api } from '../api/http';
import type { ModelConfig } from '../types/domain';
import { StatusBadge } from '../components/common/StatusBadge';
import { fmtTime } from '../utils/format';

const emptyForm = {
  name: '默认模型配置',
  provider: 'OpenAI Compatible',
  baseUrl: '',
  apiKey: '',
  chatModel: '',
  embeddingModel: '',
  embeddingDimensions: 1536,
  enabled: true,
  defaultConfig: false,
};

type FormState = typeof emptyForm;

export function ModelConfigPage() {
  const [models, setModels] = useState<ModelConfig[]>([]);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [message, setMessage] = useState('');
  const enabledCount = useMemo(() => models.filter(m => m.enabled).length, [models]);
  const defaultModel = models.find(m => m.defaultConfig);

  const load = () => api.modelConfigs().then(setModels);
  useEffect(() => { load(); }, []);

  function edit(model: ModelConfig) {
    setEditingId(model.id);
    setForm({
      name: model.name,
      provider: model.provider,
      baseUrl: model.baseUrl,
      apiKey: '',
      chatModel: model.chatModel,
      embeddingModel: model.embeddingModel,
      embeddingDimensions: model.embeddingDimensions,
      enabled: model.enabled,
      defaultConfig: model.defaultConfig,
    });
    setMessage('编辑模式：API Key 留空表示不修改原 Key。');
  }

  function reset() {
    setEditingId(null);
    setForm(emptyForm);
    setMessage('');
  }

  async function save() {
    if (!form.baseUrl || !form.chatModel || !form.embeddingModel) {
      setMessage('Base URL、对话模型 ID、向量模型 ID 必填。');
      return;
    }
    if (editingId) await api.updateModelConfig(editingId, form);
    else await api.createModelConfig(form);
    reset();
    await load();
  }

  return (
    <>
      <div className="grid stat-grid">
        <div className="card"><div className="card-label">配置数量</div><div className="card-value">{models.length}</div></div>
        <div className="card"><div className="card-label">启用中</div><div className="card-value">{enabledCount}</div></div>
        <div className="card"><div className="card-label">默认配置</div><div className="card-value">{defaultModel?.name || '-'}</div></div>
      </div>

      <div className="card">
        <div className="card-head"><span>{editingId ? '编辑模型配置' : '新增模型配置'}</span><button className="btn ghost" onClick={reset}>清空</button></div>
        <div className="model-form-grid">
          <label>名称<input className="input" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>供应商<input className="input" value={form.provider} onChange={e => setForm({ ...form, provider: e.target.value })} /></label>
          <label className="wide">Base URL<input className="input" placeholder="例如：https://api.openai.com 或你的兼容网关地址" value={form.baseUrl} onChange={e => setForm({ ...form, baseUrl: e.target.value })} /></label>
          <label>API Key<input className="input" type="password" placeholder={editingId ? '留空表示不修改' : 'sk-...'} value={form.apiKey} onChange={e => setForm({ ...form, apiKey: e.target.value })} /></label>
          <label>对话模型 ID<input className="input" placeholder="例如：gpt-4o-mini / qwen-plus" value={form.chatModel} onChange={e => setForm({ ...form, chatModel: e.target.value })} /></label>
          <label>向量模型 ID<input className="input" placeholder="例如：text-embedding-3-small" value={form.embeddingModel} onChange={e => setForm({ ...form, embeddingModel: e.target.value })} /></label>
          <label>向量维度<input className="input" type="number" value={form.embeddingDimensions} onChange={e => setForm({ ...form, embeddingDimensions: Number(e.target.value || 1536) })} /></label>
          <label className="check"><input type="checkbox" checked={form.enabled} onChange={e => setForm({ ...form, enabled: e.target.checked })} /> 启用</label>
          <label className="check"><input type="checkbox" checked={form.defaultConfig} onChange={e => setForm({ ...form, defaultConfig: e.target.checked })} /> 设为默认</label>
        </div>
        <div className="row"><button className="btn" onClick={() => save().catch(e => setMessage('保存失败：' + e.message))}>{editingId ? '保存修改' : '新增配置'}</button>{message && <span className="help-text">{message}</span>}</div>
      </div>

      <div className="card">
        <div className="card-head">模型配置管理</div>
        <table className="table">
          <thead><tr><th>名称</th><th>供应商</th><th>Base URL</th><th>对话模型</th><th>向量模型</th><th>维度</th><th>Key</th><th>状态</th><th>默认</th><th>更新时间</th><th>操作</th></tr></thead>
          <tbody>{models.map(model => <tr key={model.id}>
            <td>{model.name}</td>
            <td>{model.provider}</td>
            <td><code>{model.baseUrl}</code></td>
            <td><code>{model.chatModel}</code></td>
            <td><code>{model.embeddingModel}</code></td>
            <td>{model.embeddingDimensions}</td>
            <td>{model.hasApiKey ? <code>{model.apiKeyMasked}</code> : <span className="status-bad">未配置</span>}</td>
            <td><StatusBadge tone={model.enabled ? 'ok' : 'muted'}>{model.enabled ? '启用' : '停用'}</StatusBadge></td>
            <td>{model.defaultConfig ? <StatusBadge tone="ok">默认</StatusBadge> : '-'}</td>
            <td>{fmtTime(model.updatedAt)}</td>
            <td className="actions">
              <button className="btn ghost" onClick={() => edit(model)}>编辑</button>
              <button className="btn ghost" onClick={() => (model.enabled ? api.disableModelConfig(model.id) : api.enableModelConfig(model.id)).then(load)}>{model.enabled ? '停用' : '启用'}</button>
              {!model.defaultConfig && <button className="btn ghost" onClick={() => api.setDefaultModelConfig(model.id).then(load)}>设默认</button>}
              <button className="btn danger" onClick={() => confirm('删除该模型配置？') && api.deleteModelConfig(model.id).then(load)}>删除</button>
            </td>
          </tr>)}</tbody>
        </table>
        <p className="help-text">说明：这里保存 OpenAI-compatible 大模型地址、API Key、对话模型 ID、向量模型 ID 和向量维度。摘要服务和对话工作台会优先使用“启用且默认”的模型配置；若默认配置缺失或不完整，才回退到 application.yml / env 中的 Spring AI 配置。</p>
      </div>
    </>
  );
}
