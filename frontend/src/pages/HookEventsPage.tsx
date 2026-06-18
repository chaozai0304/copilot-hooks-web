import { useEffect, useState } from 'react';
import { api } from '../api/http';
import type { HookSession } from '../types/domain';
import { fmtNum, fmtTime } from '../utils/format';

export function HookEventsPage({ onOpenSession }: { onOpenSession: (id: number) => void }) {
  const [sessions, setSessions] = useState<HookSession[]>([]);
  useEffect(() => { api.sessions(0, 50).then(data => setSessions(data.items)); }, []);
  return <div className="card"><div className="card-head">Hook 事件参数接收记录</div><table className="table"><thead><tr><th>时间</th><th>用户</th><th>Session</th><th>模型</th><th>事件</th><th>工具</th><th>Prompt</th><th>Token</th><th>说明</th></tr></thead><tbody>{sessions.map(s => <tr className="clickable" key={s.id} onClick={() => onOpenSession(s.id)}><td>{fmtTime(s.lastEventAt || s.startedAt)}</td><td>{s.userId || '-'}</td><td><div>{s.title || s.sessionId}</div><div className="mono muted-line">{s.sessionId}</div></td><td>{s.model || '-'}</td><td>{s.eventCount}</td><td>{s.toolCount}</td><td>{s.promptCount}</td><td>{fmtNum(s.totalTokens)}</td><td>点击进入详情可查看每条事件的 raw payload、toolArgs、toolResult、prompt、error、durationMs。</td></tr>)}</tbody></table></div>;
}