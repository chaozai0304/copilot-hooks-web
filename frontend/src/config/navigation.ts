import { Bot, Cable, DatabaseZap, FileJson2, KeyRound, LayoutDashboard, ListTree, MessageSquareText, Search, Settings, ShieldCheck, SlidersHorizontal, Users } from 'lucide-react';
import type { RouteKey } from '../routes';

export interface NavItem {
  key: RouteKey;
  label: string;
  icon: typeof LayoutDashboard;
  adminOnly?: boolean;
}

export interface NavGroup {
  title: string;
  items: NavItem[];
}

export const navGroups: NavGroup[] = [
  {
    title: '观测台',
    items: [
      { key: 'dashboard', label: '概览', icon: LayoutDashboard },
      { key: 'sessions', label: '调用链会话', icon: Bot },
      { key: 'hookEvents', label: 'Hook 事件参数', icon: FileJson2 },
    ],
  },
  {
    title: '智能整理',
    items: [
      { key: 'summaries', label: '内容整理', icon: DatabaseZap },
      { key: 'chat', label: '对话工作台', icon: MessageSquareText },
      { key: 'search', label: '语义检索', icon: Search },
      { key: 'mcp', label: 'MCP', icon: Cable },
    ],
  },
  {
    title: '管理',
    items: [
      { key: 'tokens', label: '我的 Token', icon: KeyRound },
      { key: 'users', label: '用户管理', icon: Users, adminOnly: true },
      { key: 'roles', label: '角色管理', icon: ShieldCheck, adminOnly: true },
      { key: 'menus', label: '菜单权限', icon: ListTree, adminOnly: true },
      { key: 'models', label: '模型配置', icon: SlidersHorizontal, adminOnly: true },
      { key: 'settings', label: '系统设置', icon: Settings, adminOnly: true },
      { key: 'docs', label: '接入说明', icon: FileJson2 },
    ],
  },
];
