import { useState } from 'react';
import { Eye, EyeOff, LockKeyhole, UserRound } from 'lucide-react';

interface LoginPageProps {
  error?: string;
  onLogin: (username: string, password: string) => Promise<void>;
}

export function LoginPage({ error, onLogin }: LoginPageProps) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [visible, setVisible] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    try { await onLogin(username.trim(), password); }
    finally { setSubmitting(false); }
  }

  return (
    <div className="login-screen">
      <div className="login-shell">
        <div className="login-brand-panel">
          <div className="login-logo app-logo"><span>Copilot</span><span className="accent-word">Hooks</span></div>
          <h1>欢迎回来</h1>
          <p className="login-subtitle">统一接收 Hook、追踪调用链、生成会话整理，并提供向量检索与 MCP 能力。</p>
          <ul className="login-feature-list">
            <li>会话 / Trace 语义视图</li>
            <li>Token、缓存 Token、AIC 统一展示</li>
            <li>内容整理 + pgvector 检索</li>
          </ul>
        </div>
        <form className="login-card" onSubmit={submit}>
          <h2>登录 copilot-hooks-web</h2>
          <label className="login-field">
            <UserRound size={19} />
            <input value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" placeholder="用户名" required />
          </label>
          <label className="login-field">
            <LockKeyhole size={19} />
            <input value={password} onChange={e => setPassword(e.target.value)} type={visible ? 'text' : 'password'} autoComplete="current-password" placeholder="密码" required />
            <button type="button" className="eye-btn" onClick={() => setVisible(v => !v)}>{visible ? <EyeOff size={18} /> : <Eye size={18} />}</button>
          </label>
          <button className="login-btn" disabled={submitting}>{submitting ? '登录中...' : '进入工作台'}</button>
          {error && <div className="login-error">{error}</div>}
          <div className="login-hint">默认管理员来自 <code>ADMIN_USERNAME</code> / <code>ADMIN_PASSWORD</code></div>
        </form>
      </div>
    </div>
  );
}
