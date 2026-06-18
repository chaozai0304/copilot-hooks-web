import { useCallback, useEffect, useState } from 'react';
import { ApiError, api, clearBasicAuth, getBasicAuth, setBasicAuth } from './api/http';
import { AppLayout } from './components/layout/AppLayout';
import { routeTitles, type RouteKey } from './routes';
import type { Me } from './types/domain';
import { DashboardPage } from './pages/DashboardPage';
import { SessionsPage } from './pages/SessionsPage';
import { SessionDetailPage } from './pages/SessionDetailPage';
import { SearchPage } from './pages/SearchPage';
import { TokensPage } from './pages/TokensPage';
import { UsersPage } from './pages/UsersPage';
import { ModelConfigPage } from './pages/ModelConfigPage';
import { DocsPage } from './pages/DocsPage';
import { LoginPage } from './pages/LoginPage';
import { PlaceholderPage } from './pages/PlaceholderPage';
import { RoleMenuPage } from './pages/RoleMenuPage';
import { HookEventsPage } from './pages/HookEventsPage';
import { SummariesPage } from './pages/SummariesPage';
import { ChatWorkbenchPage } from './pages/ChatWorkbenchPage';
import { ProfilePage } from './pages/ProfilePage';

export function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [authReady, setAuthReady] = useState(!getBasicAuth());
  const [route, setRoute] = useState<RouteKey>('dashboard');
  const [sessionId, setSessionId] = useState<number | null>(null);
  const [loginError, setLoginError] = useState('');
  const [collapsed, setCollapsed] = useState(localStorage.getItem('copilot-hooks.sidebar-collapsed') === '1');
  const [refreshKey, setRefreshKey] = useState(0);

  const loadMe = useCallback(async () => setMe(await api.me()), []);
  useEffect(() => {
    const basic = getBasicAuth();
    if (!basic) {
      setAuthReady(true);
      return;
    }
    setAuthReady(false);
    loadMe()
      .catch(() => {
        clearBasicAuth();
        setMe(null);
      })
      .finally(() => setAuthReady(true));
  }, [loadMe]);

  async function login(username: string, password: string) {
    setLoginError('');
    setBasicAuth(username, password);
    setAuthReady(false);
    try { await loadMe(); setRoute('dashboard'); }
    catch (e) { clearBasicAuth(); setLoginError(e instanceof ApiError && e.status === 401 ? '用户名或密码不正确。' : '登录失败，请检查服务状态。'); }
    finally { setAuthReady(true); }
  }

  function logout() { clearBasicAuth(); setMe(null); setAuthReady(true); }
  function openSession(id: number) { setSessionId(id); setRoute('sessionDetail'); }
  function toggleCollapsed() { setCollapsed(v => { localStorage.setItem('copilot-hooks.sidebar-collapsed', v ? '0' : '1'); return !v; }); }

  if (!authReady) return null;
  if (!me) return <LoginPage error={loginError} onLogin={login} />;
  const isAdmin = String(me.role || '').toUpperCase() === 'ADMIN';

  const page = (() => {
    if (route === 'dashboard') return <DashboardPage key={refreshKey} onOpenSession={openSession} />;
    if (route === 'sessions') return <SessionsPage key={refreshKey} me={me} onOpenSession={openSession} />;
    if (route === 'sessionDetail' && sessionId) return <SessionDetailPage key={`${sessionId}-${refreshKey}`} id={sessionId} onBack={() => setRoute('sessions')} />;
    if (route === 'hookEvents') return <HookEventsPage key={refreshKey} onOpenSession={openSession} />;
    if (route === 'summaries') return <SummariesPage key={refreshKey} me={me} onOpenSession={openSession} />;
    if (route === 'chat') return <ChatWorkbenchPage key={refreshKey} />;
    if (route === 'search') return <SearchPage key={refreshKey} onOpenSession={openSession} />;
    if (route === 'tokens') return <TokensPage key={refreshKey} />;
    if (route === 'profile') return <ProfilePage me={me} onUpdated={setMe} />;
    if (route === 'users') return isAdmin ? <UsersPage key={refreshKey} /> : <PlaceholderPage title="用户管理" description="仅管理员可以管理用户；普通用户只能管理自己的 Token 和查看自己的会话。" />;
    if (route === 'roles') return isAdmin ? <RoleMenuPage mode="roles" /> : <PlaceholderPage title="角色管理" description="仅管理员可以查看和维护角色与菜单的对应关系。" />;
    if (route === 'menus') return isAdmin ? <RoleMenuPage mode="menus" /> : <PlaceholderPage title="菜单权限" description="仅管理员可以查看和维护菜单权限。" />;
    if (route === 'models') return isAdmin ? <ModelConfigPage key={refreshKey} /> : <PlaceholderPage title="模型配置管理" description="仅管理员可以配置大模型 baseUrl、API Key、模型 ID 和向量模型。" />;
    if (route === 'docs' || route === 'mcp') return <DocsPage />;
    return <PlaceholderPage title={routeTitles[route]} description="该功能菜单已纳入标准前端框架，后续可在 src/pages 下独立扩展。" />;
  })();

  return <AppLayout me={me} route={route} collapsed={collapsed} onNavigate={setRoute} onOpenProfile={() => setRoute('profile')} onToggleCollapse={toggleCollapsed} onRefresh={() => setRefreshKey(k => k + 1)} onLogout={logout}>{page}</AppLayout>;
}
