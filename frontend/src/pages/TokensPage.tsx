import { useEffect, useState } from 'react';
import { api } from '../api/http';
import type { ApiToken } from '../types/domain';
import { fmtTime, toIsoOrNull } from '../utils/format';
import { StatusBadge } from '../components/common/StatusBadge';

export function TokensPage() {
  const [tokens, setTokens] = useState<ApiToken[]>([]);
  const [name, setName] = useState('personal-cli');
  const [expiresAt, setExpiresAt] = useState('');
  const [neverExpire, setNeverExpire] = useState(true);
  const [created, setCreated] = useState('');
  const load = () => api.tokens().then(setTokens);
  useEffect(() => { load(); }, []);
  async function create() { const r = await api.createToken({ name, expiresAt: toIsoOrNull(expiresAt, neverExpire) }); setCreated(r.token); await load(); }
  return <div className="card"><div className="card-head">个人 Token</div><div className="row"><input className="input" value={name} onChange={e => setName(e.target.value)} placeholder="名称" /><input className="input" type="datetime-local" disabled={neverExpire} value={expiresAt} onChange={e => setExpiresAt(e.target.value)} /><label className="check"><input type="checkbox" checked={neverExpire} onChange={e => setNeverExpire(e.target.checked)} /> 永不过期</label><button className="btn" onClick={create}>创建 Token</button></div>{created && <div className="notice">新建 Token（仅显示一次）：<br /><code>{created}</code></div>}<table className="table"><thead><tr><th>名称</th><th>前缀</th><th>过期</th><th>最近使用</th><th>状态</th><th>操作</th></tr></thead><tbody>{tokens.map(t => <tr key={t.id}><td>{t.name}</td><td><code>{t.prefix}…</code></td><td>{t.expiresAt ? fmtTime(t.expiresAt) : '永不过期'}</td><td>{t.lastUsedAt ? fmtTime(t.lastUsedAt) : '-'}</td><td><StatusBadge tone={t.revoked ? 'bad' : 'ok'}>{t.revoked ? '已吊销' : '有效'}</StatusBadge></td><td>{!t.revoked && <button className="btn ghost" onClick={() => api.revokeToken(t.id).then(load)}>吊销</button>}</td></tr>)}</tbody></table></div>;
}
