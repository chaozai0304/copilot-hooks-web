import { useEffect, useMemo, useState } from 'react';
import { api } from '../api/http';
import type { HookSession, Me, UserAccount } from '../types/domain';
import { fmtDuration, fmtNum, fmtTime } from '../utils/format';

interface Props {
  me: Me;
  onOpenSession: (id: number) => void;
}

export function SessionsPage({ me, onOpenSession }: Props) {
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState('');
  const [sessions, setSessions] = useState<HookSession[]>([]);
  const [total, setTotal] = useState(0);
  const [month, setMonth] = useState('');
  const [day, setDay] = useState('');
  const [users, setUsers] = useState<UserAccount[]>([]);
  const [userId, setUserId] = useState<number | ''>('');
  const pageSize = 20;
  const isAdmin = String(me.role || '').toUpperCase() === 'ADMIN';

  useEffect(() => {
    if (!isAdmin) return;
    api.users().then(setUsers).catch(console.error);
  }, [isAdmin]);

  useEffect(() => {
    api.sessions(page, pageSize, {
      month: month || undefined,
      day: day || undefined,
      userId: isAdmin && userId !== '' ? Number(userId) : undefined,
    }).then(data => {
      setSessions(data.items);
      setTotal(data.total);
    });
  }, [page, pageSize, month, day, isAdmin, userId]);

  const filtered = useMemo(() => sessions.filter(s => !query || `${s.title || ''} ${s.sessionId} ${s.initialPrompt || ''} ${(s.tags || []).join(' ')}`.toLowerCase().includes(query.toLowerCase())), [sessions, query]);

  function onMonthChange(value: string) {
    setMonth(value);
    if (value) setDay('');
    setPage(0);
  }

  function onDayChange(value: string) {
    setDay(value);
    if (value) setMonth('');
    setPage(0);
  }

  return <div className="card">
    <div className="card-head">会话列表</div>
    <div className="row wrap gap-sm filter-row">
      <input className="input" placeholder="搜索标题/标签..." value={query} onChange={e => setQuery(e.target.value)} />
      <label className="inline-field">按月
        <input className="input" type="month" value={month} onChange={e => onMonthChange(e.target.value)} />
      </label>
      <label className="inline-field">按天
        <input className="input" type="date" value={day} onChange={e => onDayChange(e.target.value)} />
      </label>
      {isAdmin && <label className="inline-field">用户
        <select className="input" value={userId} onChange={e => { setUserId(e.target.value ? Number(e.target.value) : ''); setPage(0); }}>
          <option value="">全部用户</option>
          {users.map(u => <option key={u.id} value={u.id}>{u.username}{u.displayName ? ` (${u.displayName})` : ''}</option>)}
        </select>
      </label>}
      <button className="btn ghost" onClick={() => { setMonth(''); setDay(''); setUserId(''); setPage(0); }}>重置筛选</button>
    </div>
    <table className="table"><thead><tr><th>开始</th><th>标题/会话</th><th>本次问题</th><th>标签</th><th>模型</th><th>事件</th><th>工具</th><th>错误</th><th>Token</th><th>耗时</th></tr></thead>
      <tbody>{filtered.map(s => <tr className="clickable" key={s.id} onClick={() => onOpenSession(s.id)}><td>{fmtTime(s.startedAt)}</td><td><div>{s.title || '(未生成摘要)'}</div><div className="mono muted-line">{s.sessionId}</div></td><td><div className="prompt-snippet">{s.initialPrompt || '-'}</div></td><td>{(s.tags || []).map(t => <span className="tag" key={t}>{t}</span>)}</td><td>{s.model || '-'}</td><td>{s.eventCount}</td><td>{s.toolCount}</td><td className={s.errorCount ? 'status-bad' : ''}>{s.errorCount}</td><td>{fmtNum(s.totalTokens)}</td><td>{fmtDuration(s.durationMs)}</td></tr>)}</tbody></table>
    <div className="pager"><button className="btn ghost" disabled={page === 0} onClick={() => setPage(p => p - 1)}>上一页</button><span>第 {page + 1} 页 / 共 {Math.max(1, Math.ceil(total / pageSize))} 页（{total} 条）</span><button className="btn ghost" disabled={(page + 1) * pageSize >= total} onClick={() => setPage(p => p + 1)}>下一页</button></div>
  </div>;
}
