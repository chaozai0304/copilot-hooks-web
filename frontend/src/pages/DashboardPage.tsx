import { useEffect, useState } from 'react';
import { api } from '../api/http';
import type { HookSession } from '../types/domain';
import { fmtDuration, fmtNum, fmtTime } from '../utils/format';

interface Props { onOpenSession: (id: number) => void; }

export function DashboardPage({ onOpenSession }: Props) {
  const [sessions, setSessions] = useState<HookSession[]>([]);
  const [total, setTotal] = useState(0);

  useEffect(() => { api.sessions(0, 20).then(data => { setSessions(data.items); setTotal(data.total); }); }, []);
  const totalEvents = sessions.reduce((sum, item) => sum + item.eventCount, 0);
  const totalTokens = sessions.reduce((sum, item) => sum + Number(item.totalTokens || 0), 0);

  return (
    <>
      <div className="grid stat-grid">
        <div className="card"><div className="card-label">总会话数</div><div className="card-value">{fmtNum(total)}</div></div>
        <div className="card"><div className="card-label">总事件数</div><div className="card-value">{fmtNum(totalEvents)}</div></div>
        <div className="card"><div className="card-label">总 Token</div><div className="card-value">{fmtNum(totalTokens)}</div></div>
        <div className="card"><div className="card-label">已索引摘要</div><div className="card-value">{fmtNum(sessions.filter(s => s.title).length)}</div></div>
      </div>
      <div className="card"><div className="card-head">最近会话</div>
        <table className="table"><thead><tr><th>开始时间</th><th>会话</th><th>本次问题</th><th>模型</th><th>事件</th><th>工具</th><th>错误</th><th>Token</th><th>耗时</th></tr></thead>
          <tbody>{sessions.slice(0, 10).map(s => <tr className="clickable" key={s.id} onClick={() => onOpenSession(s.id)}>
            <td>{fmtTime(s.startedAt)}</td><td><div>{s.title || s.sessionId}</div><div className="mono muted-line">{s.sessionId}</div></td><td><div className="prompt-snippet">{s.initialPrompt || '-'}</div></td><td>{s.model || '-'}</td><td>{s.eventCount}</td><td>{s.toolCount}</td><td className={s.errorCount ? 'status-bad' : ''}>{s.errorCount}</td><td>{fmtNum(s.totalTokens)}</td><td>{fmtDuration(s.durationMs)}</td>
          </tr>)}</tbody></table>
      </div>
    </>
  );
}
