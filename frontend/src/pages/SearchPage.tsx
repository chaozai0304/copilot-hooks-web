import { useState } from 'react';
import { api } from '../api/http';
import type { SearchHit } from '../types/domain';

interface Props { onOpenSession: (id: number) => void; }

export function SearchPage({ onOpenSession }: Props) {
  const [q, setQ] = useState('');
  const [results, setResults] = useState<SearchHit[]>([]);
  async function search() { if (q.trim()) setResults(await api.search(q.trim(), 10)); }
  return <div className="card"><div className="card-head">语义检索</div><div className="row"><input className="input grow" value={q} onChange={e => setQ(e.target.value)} placeholder="例如：修复了 OAuth 回调失败的会话" /><button className="btn" onClick={search}>检索</button></div><div className="results">{results.map(r => <div className="result" key={r.id} onClick={() => r.metadata?.sessionDbId && onOpenSession(Number(r.metadata.sessionDbId))}><div className="result-title">{r.metadata?.title || r.id}</div><div className="result-meta">score {(r.score || 0).toFixed(3)} · session {r.metadata?.sessionId}</div><div className="result-snippet">{r.content}</div></div>)}</div></div>;
}
