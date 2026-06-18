import { navGroups } from '../config/navigation';

const roleMenus = [
  { role: 'SUPER_ADMIN / ADMIN', desc: '查看所有用户对话，管理用户、角色、菜单、模型配置，执行内容整理和向量存储。', menus: navGroups.flatMap(g => g.items.map(i => i.label)) },
  { role: 'USER', desc: '只能查看自己的 Hook 数据、Token、会话、摘要和语义检索结果。', menus: navGroups.flatMap(g => g.items).filter(i => !i.adminOnly).map(i => i.label) },
];

export function RoleMenuPage({ mode }: { mode: 'roles' | 'menus' }) {
  return <div className="card"><div className="card-head">{mode === 'roles' ? '角色管理' : '菜单权限'}</div><table className="table"><thead><tr><th>角色</th><th>说明</th><th>可访问菜单</th></tr></thead><tbody>{roleMenus.map(row => <tr key={row.role}><td><strong>{row.role}</strong></td><td>{row.desc}</td><td>{row.menus.map(m => <span className="tag" key={m}>{m}</span>)}</td></tr>)}</tbody></table><p className="help-text">当前后端用户表使用 role 字段控制 ADMIN/USER；本页明确角色和菜单对应关系，后续可以扩展为数据库化角色-菜单权限。</p></div>;
}