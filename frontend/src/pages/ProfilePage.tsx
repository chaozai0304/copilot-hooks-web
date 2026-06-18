import { useEffect, useState } from 'react';
import { api } from '../api/http';
import type { Me } from '../types/domain';

interface ProfilePageProps {
  me: Me;
  onUpdated: (next: Me) => void;
}

export function ProfilePage({ me, onUpdated }: ProfilePageProps) {
  const [displayName, setDisplayName] = useState(me.displayName || '');
  const [email, setEmail] = useState(me.email || '');
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setDisplayName(me.displayName || '');
    setEmail(me.email || '');
  }, [me.displayName, me.email]);

  async function save() {
    if (newPassword && newPassword !== confirmPassword) {
      alert('两次输入的新密码不一致。');
      return;
    }
    setSaving(true);
    try {
      const next = await api.updateMe({
        displayName,
        email,
        currentPassword: currentPassword || undefined,
        newPassword: newPassword || undefined,
      });
      onUpdated(next);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      alert('个人信息已更新。');
    } catch (error) {
      alert(error instanceof Error ? error.message : '更新失败');
    } finally {
      setSaving(false);
    }
  }

  return <div className="card profile-card">
    <div className="card-head">个人信息</div>
    <div className="model-form-grid">
      <label>用户名（不可修改）
        <input className="input" value={me.username} disabled />
      </label>
      <label>角色
        <input className="input" value={me.role} disabled />
      </label>
      <label>显示名称
        <input className="input" value={displayName} onChange={e => setDisplayName(e.target.value)} placeholder="用于页面展示，可选" />
      </label>
      <label>邮箱
        <input className="input" value={email} onChange={e => setEmail(e.target.value)} placeholder="用于联系，可选" />
      </label>
      <label>当前密码（仅修改密码时必填）
        <input className="input" type="password" value={currentPassword} onChange={e => setCurrentPassword(e.target.value)} />
      </label>
      <label>新密码
        <input className="input" type="password" value={newPassword} onChange={e => setNewPassword(e.target.value)} placeholder="留空则不修改" />
      </label>
      <label>确认新密码
        <input className="input" type="password" value={confirmPassword} onChange={e => setConfirmPassword(e.target.value)} placeholder="再次输入新密码" />
      </label>
    </div>
    <div className="row">
      <button className="btn" disabled={saving} onClick={save}>{saving ? '保存中...' : '保存修改'}</button>
    </div>
  </div>;
}
