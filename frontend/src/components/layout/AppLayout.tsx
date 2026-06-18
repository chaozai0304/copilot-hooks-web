import { ChevronLeft, RefreshCcw, Search } from 'lucide-react';
import type { Me } from '../../types/domain';
import { navGroups } from '../../config/navigation';
import { routeTitles, type RouteKey } from '../../routes';

interface AppLayoutProps {
  me: Me;
  route: RouteKey;
  collapsed: boolean;
  onNavigate: (route: RouteKey) => void;
  onOpenProfile: () => void;
  onToggleCollapse: () => void;
  onRefresh: () => void;
  onLogout: () => void;
  children: React.ReactNode;
}

export function AppLayout({ me, route, collapsed, onNavigate, onOpenProfile, onToggleCollapse, onRefresh, onLogout, children }: AppLayoutProps) {
  const isAdmin = String(me.role || '').toUpperCase() === 'ADMIN';
  return (
    <div className={`shell ${collapsed ? 'is-collapsed' : ''}`}>
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">CH</div>
          <div className="brand-text">
            <div className="brand-title">copilot-hooks-web</div>
            <div className="brand-sub">Trace Console v0.1</div>
          </div>
          <button className="collapse-btn" onClick={onToggleCollapse} title="收缩菜单"><ChevronLeft size={18} /></button>
        </div>
        <nav className="nav">
          {navGroups.map(group => (
            <div key={group.title} className="nav-group">
              <div className="nav-group-title">{group.title}</div>
              {group.items.filter(item => !item.adminOnly || isAdmin).map(item => {
                const Icon = item.icon;
                return (
                  <button key={item.key} className={`nav-item ${route === item.key ? 'active' : ''}`} onClick={() => onNavigate(item.key)}>
                    <Icon className="nav-ico" size={17} />
                    <span className="nav-text">{item.label}</span>
                  </button>
                );
              })}
            </div>
          ))}
        </nav>
        <div className="user-box">
          <button className="uname-link" onClick={onOpenProfile} title="进入个人信息">{me.displayName || me.username}</button>
          <div className="user-meta">角色：{me.role}</div>
          <div className="user-meta">{me.authType || 'BASIC'}</div>
        </div>
      </aside>
      <main className="main">
        <header className="topbar">
          <div>
            <div className="crumb">{routeTitles[route]}</div>
            <div className="top-sub">Copilot Hook 事件观测与内容整理</div>
          </div>
          <div className="topbar-actions">
            <button className="icon-btn" title="搜索" onClick={() => onNavigate('search')}><Search size={18} /></button>
            <button className="icon-btn" title="刷新" onClick={onRefresh}><RefreshCcw size={18} /></button>
            <button className="btn ghost" onClick={onLogout}>退出</button>
          </div>
        </header>
        <section className="view">{children}</section>
      </main>
    </div>
  );
}
