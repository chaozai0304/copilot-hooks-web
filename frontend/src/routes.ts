export type RouteKey =
  | 'dashboard'
  | 'sessions'
  | 'sessionDetail'
  | 'hookEvents'
  | 'summaries'
  | 'chat'
  | 'search'
  | 'tokens'
  | 'users'
  | 'roles'
  | 'menus'
  | 'models'
  | 'mcp'
  | 'profile'
  | 'settings'
  | 'docs';

export const routeTitles: Record<RouteKey, string> = {
  dashboard: '概览',
  sessions: '会话历史',
  sessionDetail: '会话详情',
  hookEvents: 'Hook 事件参数',
  summaries: '内容整理与向量存储',
  chat: '对话工作台',
  search: '语义检索',
  tokens: '我的 Token',
  users: '用户管理',
  roles: '角色管理',
  menus: '菜单权限',
  models: '模型配置管理',
  mcp: 'MCP',
  profile: '个人信息',
  settings: '系统设置',
  docs: '接入说明',
};
