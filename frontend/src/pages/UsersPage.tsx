import { useEffect, useState } from 'react';
import { api } from '../api/http';
import type { UserAccount } from '../types/domain';
import { StatusBadge } from '../components/common/StatusBadge';

export function UsersPage() {
  const [users, setUsers] = useState<UserAccount[]>([]);
  const [form, setForm] = useState({ username: '', displayName: '', email: '', password: '', role: 'USER' });
  const [importing, setImporting] = useState(false);
  const [importDialogOpen, setImportDialogOpen] = useState(false);
  const [importFile, setImportFile] = useState<File | null>(null);

  const load = () => api.users().then(setUsers);

  useEffect(() => {
    load();
  }, []);

  async function create() {
    await api.createUser(form);
    setForm({ username: '', displayName: '', email: '', password: '', role: 'USER' });
    await load();
  }

  async function importExcel(file?: File | null) {
    if (!file) return;
    setImporting(true);
    try {
      const r = await api.importUsersExcel(file);
      const errorText = (r.errors || []).slice(0, 10).map(e => `第 ${e.row} 行：${e.message}`).join('\n');
      alert(`已创建 ${r.created} 条，跳过 ${r.skipped} 条${errorText ? `\n\n问题明细：\n${errorText}` : ''}`);
      setImportDialogOpen(false);
      setImportFile(null);
      await load();
    } finally {
      setImporting(false);
    }
  }

  async function downloadTemplate() {
    const blob = await api.downloadUserImportTemplate();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'user-import-template.xlsx';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  return <div className="card">
    {importing && <div className="summary-loading-mask" role="status" aria-live="polite">
      <div className="summary-loading-card">
        <span className="summary-spinner"></span>
        <b>正在导入用户</b>
        <small>正在读取 Excel 并写入用户数据，请稍候...</small>
      </div>
    </div>}

    <div className="card-head">用户管理（仅 ADMIN）</div>
    <div className="row wrap gap-sm users-toolbar">
      <input className="input" placeholder="用户名" value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} />
      <input className="input" placeholder="显示名" value={form.displayName} onChange={e => setForm({ ...form, displayName: e.target.value })} />
      <input className="input" placeholder="邮箱" value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} />
      <input className="input" type="password" placeholder="初始密码" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} />
      <select className="input" value={form.role} onChange={e => setForm({ ...form, role: e.target.value })}><option>USER</option><option>ADMIN</option></select>
      <button className="btn" onClick={create}>新增</button>
      <button className="btn ghost" onClick={() => setImportDialogOpen(true)}>批量导入</button>
    </div>

    {importDialogOpen && <div className="summary-loading-mask admin-import-modal-mask" onClick={() => !importing && setImportDialogOpen(false)}>
      <div className="admin-import-dialog" onClick={e => e.stopPropagation()}>
        <div className="card-head"><span>Excel 批量导入</span><button className="btn ghost" disabled={importing} onClick={() => setImportDialogOpen(false)}>关闭</button></div>
        <p className="help-text">支持 `.xlsx/.xls` 第一张工作表，表头可使用：`username/用户名`、`displayName/显示名/姓名`、`email/邮箱`、`password/密码`、`role/角色`、`enabled/启用`。</p>
        <div className="admin-import-actions">
          <button className="btn ghost" disabled={importing} onClick={() => downloadTemplate().catch(err => alert('模板下载失败：' + err.message))}>下载模板</button>
          <input className="input" type="file" accept=".xlsx,.xls" disabled={importing} onChange={e => setImportFile(e.target.files?.[0] || null)} />
        </div>
        <div className="help-text">当前文件：{importFile?.name || '未选择文件'}</div>
        <div className="row">
          <button className="btn" disabled={!importFile || importing} onClick={() => importExcel(importFile).catch(err => alert('Excel 导入失败：' + err.message))}>{importing ? '导入中...' : '确认导入'}</button>
          <button className="btn ghost" disabled={importing} onClick={() => { setImportDialogOpen(false); setImportFile(null); }}>取消</button>
        </div>
      </div>
    </div>}

    <table className="table">
      <thead><tr><th>ID</th><th>用户名</th><th>显示名</th><th>邮箱</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
      <tbody>{users.map(u => <tr key={u.id}>
        <td>{u.id}</td>
        <td>{u.username}</td>
        <td>{u.displayName || '-'}</td>
        <td>{u.email || '-'}</td>
        <td>{u.role}</td>
        <td><StatusBadge tone={u.enabled ? 'ok' : 'bad'}>{u.enabled ? '启用' : '禁用'}</StatusBadge></td>
        <td>
          <div className="row">
            {u.enabled
              ? <button className="btn ghost" onClick={() => api.disableUser(u.id).then(load)}>禁用</button>
              : <button className="btn ghost" onClick={() => api.enableUser(u.id).then(load)}>启用</button>}
            <button className="btn ghost" onClick={() => {
              const name = prompt('Token 名称', `${u.username}-token`) || '';
              if (!name.trim()) return;
              api.issueUserToken(u.id, name.trim()).then(r => alert(`只显示一次，请妥善保存：\n${r.token}`));
            }}>签发 Token</button>
            <button className="btn ghost" onClick={() => {
              if (!confirm(`确认删除用户 ${u.username}？`)) return;
              api.deleteUser(u.id).then(load);
            }}>删除</button>
          </div>
        </td>
      </tr>)}</tbody>
    </table>
  </div>;
}
