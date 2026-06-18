# Copilot Hooks Web

一个用于接收 GitHub Copilot CLI / 云端 Agent **Hook 事件**，存储完整调用链，并以 AI 摘要 + 向量检索 + MCP 暴露给后续使用的 Spring Boot 服务。

## 功能

- **HTTP Hook 接入**：兼容 Copilot 官方 `type: "http"` Hook（同时识别 camelCase 与 PascalCase/VS Code 兼容格式）。
- **完整调用链**：保存每个会话的 sessionStart / userPromptSubmitted / preToolUse / postToolUse / postToolUseFailure / agentStop / sessionEnd / errorOccurred / preCompact / notification / subagent* 事件，原始 JSON 全部留档。
- **统计字段**：模型、工具名、参数、结果、错误信息、耗时、输入/输出/总 Token。
- **会话筛选与详情范围**：调用链会话和内容整理页面支持按月/按天筛选；管理员可按用户筛选；会话详情默认加载最近一周并可切换查看全部。
- **AI 摘要 + 标签**：会话结束自动触发；摘要内容（标题 / summary / 关键点 / 标签）通过 LLM 生成，并以向量形式写入 pgvector。
- **对话工作台**：支持按已配置模型发起对话，调节 Temperature / Top P / Max Tokens，并默认启用全部 MCP 工具或手动选择指定工具。
- **多用户**：每个用户多 Token，可设过期或永不过期，可吊销，Token 仅显示一次。
- **Excel 批量导入用户**：管理员可通过 Excel 一次性导入用户名、显示名、邮箱、密码、角色、启用状态等字段。
- **个人信息维护**：左下角用户名可进入个人信息页，更新显示名/邮箱/密码。
- **MCP Server**：SSE 端点 `/mcp/sse`，Bearer Token 鉴权，工具：`search_sessions / list_recent_sessions / get_session`。
- **前端**：左侧菜单 SPA（概览 / 会话历史 / 语义检索 / Token / 用户管理 / 接入说明），严肃深色风格，Docker 中以 volume 挂载，可热替换。
- **管理员**：可创建用户、为任意用户签发 Token、启/停用、删除、Excel 导入。

## 快速开始

```bash
# 1. 复制 .env 模板（按需修改）
cp .env.example .env

# 2. 启动
docker compose up -d --build

# 3. 打开 http://<host>:8080，使用 .env 中 ADMIN_USERNAME / ADMIN_PASSWORD 登录
#    在 "我的 Token" 创建一个 Token，复制到示例 hook 文件中
```

### 环境变量

| 名称 | 默认 | 说明 |
| --- | --- | --- |
| `DATABASE_JDBC_URL` | `jdbc:postgresql://172.17.1.1:5432/copilot_hooks?...` | JDBC URL，需开启 pgvector 扩展 |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `aiuser` / `1qa@WS3ed` | 数据库凭据 |
| `AI_BASE_URL` | `https://api.openai.com` | LLM/Embedding 服务（OpenAI 兼容） |
| `AI_API_KEY` | `changeme` | API Key |
| `AI_CHAT_MODEL` | `gpt-4o-mini` | 摘要使用的模型 |
| `AI_EMBED_MODEL` | `text-embedding-3-small` | 向量模型 |
| `AI_EMBED_DIMENSIONS` | `1536` | 向量维度，必须与 embedding 模型一致 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / `admin123` | 初始管理员（启动后请尽快修改） |
| `SUMMARY_AUTO` | `true` | 会话结束是否自动生成摘要 |
| `FRONTEND_PATH` | `file:/app/frontend/`（容器） | 前端目录，挂载替换以热更新 |

> **改变向量维度**：先 `docker compose down -v`（如需）或手动 `ALTER TABLE summary_embeddings ALTER COLUMN embedding TYPE vector(<新维度>);`，再重启。

### 接入 Copilot CLI

把 [`examples/copilot-hooks-web.json`](examples/copilot-hooks-web.json) 拷贝到 `~/.copilot/hooks/`（macOS/Linux）或 `%USERPROFILE%\.copilot\hooks\`（Windows），把 `YOUR_TOKEN_HERE` 改成你在 Web 控制台创建的 Token，然后重启 Copilot CLI。

或在仓库根 `.github/hooks/<name>.json` 中以同样格式配置（提交进仓库后所有协作者都会上报，注意 Token 暴露风险，建议每人用自己的 user-level hook）。

### MCP 接入

任何支持 MCP SSE 的客户端均可：

```
URL    : http(s)://<host>:8080/mcp/sse
Header : Authorization: Bearer <你的 Token>
```

只能查询自己 Token 对应用户的数据。

## 项目结构

```
src/main/java/com/copilot/hooks/
  ├─ HooksApplication.java
  ├─ config/        SecurityConfig, WebConfig
  ├─ controller/    HookController, SessionController, TokenController, AdminController, SearchController, MeController, HealthController
  ├─ domain/        User, ApiToken, HookSession, HookEvent, SessionSummary
  ├─ repository/    Spring Data JPA
  ├─ security/      BearerTokenAuthFilter, TokenService, CurrentUser, DbUserDetailsService, AppPrincipal
  ├─ service/       HookIngestService, SummaryService
  └─ mcp/           HookMcpTools, McpConfig
src/main/resources/
  ├─ application.yml
  └─ db/migration/V1__init.sql
frontend/             # 严肃风格的 SPA，可 Docker volume 替换
examples/             # 示例 hook 配置
Dockerfile / docker-compose.yml
```

## 最近新增

- 用户管理支持 `.xlsx/.xls` 批量导入。
- 新增“对话工作台”菜单，可选择已启用模型并配置常用推理参数。
- 对话工作台默认启用全部 MCP 工具，也可以手动勾选部分工具。
- 修复刷新页面时先跳登录页再回首页的闪屏问题。
