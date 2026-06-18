import { useEffect, useState } from 'react';
import { api } from '../api/http';
import type { HookSession, Me, UserAccount } from '../types/domain';
import { fmtTime } from '../utils/format';

export function SummariesPage({ me, onOpenSession }: { me: Me; onOpenSession: (id: number) => void }) {
  const [sessions, setSessions] = useState<HookSession[]>([]);
  const [month, setMonth] = useState('');
  const [day, setDay] = useState('');
  const [users, setUsers] = useState<UserAccount[]>([]);
  const [userId, setUserId] = useState<number | ''>('');
  const isAdmin = String(me.role || '').toUpperCase() === 'ADMIN';

  useEffect(() => {
    if (!isAdmin) return;
    api.users().then(setUsers).catch(console.error);
  }, [isAdmin]);

  useEffect(() => {
    api.sessions(0, 100, {
      month: month || undefined,
      day: day || undefined,
      userId: isAdmin && userId !== '' ? Number(userId) : undefined,
    }).then(data => setSessions(data.items));
  }, [month, day, isAdmin, userId]);

  function onMonthChange(value: string) {
    setMonth(value);
    if (value) setDay('');
  }

  function onDayChange(value: string) {
    setDay(value);
    if (value) setMonth('');
  }

  return <div className="card">
    <div className="card-head">内容整理与向量存储</div>
    <div className="feature-banner"><div><b>现在支持对话工作台</b><p className="help-text">可在左侧菜单进入“对话工作台”，选择模型、调节常用参数，并默认启用全部 MCP 工具来辅助内容整理与排障。</p></div></div>
    <div className="row wrap gap-sm filter-row">
      <label className="inline-field">按月
        <input className="input" type="month" value={month} onChange={e => onMonthChange(e.target.value)} />
      </label>
      <label className="inline-field">按天
        <input className="input" type="date" value={day} onChange={e => onDayChange(e.target.value)} />
      </label>
      {isAdmin && <label className="inline-field">用户
        <select className="input" value={userId} onChange={e => setUserId(e.target.value ? Number(e.target.value) : '')}>
          <option value="">全部用户</option>
          {users.map(u => <option key={u.id} value={u.id}>{u.username}{u.displayName ? ` (${u.displayName})` : ''}</option>)}
        </select>
      </label>}
      <button className="btn ghost" onClick={() => { setMonth(''); setDay(''); setUserId(''); }}>重置筛选</button>
    </div>
    <table className="table"><thead><tr><th>更新时间</th><th>用户</th><th>标题</th><th>标签</th><th>模型</th><th>向量状态</th><th>操作</th></tr></thead><tbody>{sessions.map(s => <tr key={s.id}><td>{fmtTime(s.lastEventAt)}</td><td>{s.userId || '-'}</td><td>{s.title || '(未整理)'}</td><td>{(s.tags || []).map(t => <span className="tag" key={t}>{t}</span>)}</td><td>{s.model || '-'}</td><td>{s.title ? <span className="status-ok">已写入 pgvector</span> : <span className="status-muted">待整理</span>}</td><td><button className="btn ghost" onClick={() => onOpenSession(s.id)}>查看/重新整理</button></td></tr>)}</tbody></table><p className="help-text">管理员可以看到所有用户会话，并在会话详情中点击“重新生成摘要”，生成内容整理结果并写入向量表。</p></div>;
}
