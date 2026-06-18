# copilot-hooks-web 全局开发指令

本仓库是 `copilot-hooks-web`，用于接收、存储、展示和分析 Copilot/VS Code Agent Hook 事件，并提供摘要、向量检索和 MCP 查询能力。

## 必读文档

在开发新功能、修改现有功能或排查复杂问题前，必须优先阅读：

- `docs/PROJECT_FUNCTIONAL_SPEC.md`
- 与当前任务相关的后端、前端、Hook 脚本、数据库迁移文件

如果功能说明文档与代码不一致，应以当前代码为准，并在本次修改中同步修正文档。

## 文档维护要求

任何新增功能、删除功能、行为变更、接口变更、数据库结构变更、前端页面变更、Hook 字段解析变更、模型/向量/MCP 行为变更，都必须同步更新：

- `docs/PROJECT_FUNCTIONAL_SPEC.md`

如果影响用户接入、部署或使用方式，也要同步更新：

- `README.md`
- 前端接入说明页面：`frontend/src/pages/DocsPage.tsx`

不要只改代码不改说明文档。

## 项目边界

- 项目名称固定为 `copilot-hooks-web`。
- 不要引入其他业务系统的名称、品牌、菜单或视觉风格。
- 可以参考其他系统的交互体验，但不能照抄业务命名和文案。
- 目标是 Copilot Hook 可观测、摘要、检索和 MCP 暴露，不是通用日志平台。

## 后端开发规范

- 使用 Java 21、Spring Boot 3.4.1、Spring AI 1.0.0。
- 数据库结构变更必须新增 Flyway migration，不能依赖 Hibernate 自动建表或改表。
- 保持 `spring.jpa.hibernate.ddl-auto=validate` 的设计。
- Controller 返回给前端的数据需要考虑普通用户和管理员权限差异。
- 普通用户只能访问自己的会话、事件、摘要和 Token。
- 管理员可以管理用户、模型配置和查看全局数据。
- 涉及用户数据的 REST/MCP 查询必须使用当前认证用户做隔离。
- Hook ingest 必须尽量保留原始 payload，方便后续排查字段兼容问题。
- 新增可观测字段时，通常需要同步修改：
  - `HookEvent`
  - `HookSession`
  - `HookIngestService`
  - Flyway migration
  - `SessionController`
  - `frontend/src/types/domain.ts`
  - 相关前端展示页面

## AI 模型与向量规范

- 涉及摘要、内容整理、向量生成等 AI 调用时，优先使用模型配置页面中“启用且默认”的模型配置。
- 只有没有默认模型配置或默认配置不完整时，才回退 `application.yml` 中的 `spring.ai.openai.*` 配置。
- system prompt 默认使用中文。
- 面向摘要的模型输出应尽量稳定、结构化，优先严格 JSON。
- 向量写入使用 `summary_embeddings`，其 `id` 是 UUID。
- 不要用普通数字字符串作为 PgVectorStore 的 Document ID。
- 对同一会话重复生成摘要时，应使用稳定文档 ID 覆盖同一条向量。
- 修改 embedding 模型或维度时，必须确认 pgvector 表维度匹配。

## Hook 与 Trace 规范

- Hook payload 本身通常不包含完整 Token、费用、最终回复和工具详情。
- 本机 `.github/hooks/send-hook.js` 负责读取 transcript 和 Copilot debug log 后补全字段。
- Token、缓存 Token、AIC、TTFT、模型名等信息通常来自 `debug-logs/<session>/main.jsonl` 的 `llm_request`。
- 解析 `main.jsonl` 时不要简单取最近 LLM，应按当前用户 Prompt 匹配 user_message 窗口。
- 会话详情页 Trace 应按语义节点展示：
  - User
  - LLM
  - Tool
  - Subagent
  - Hook
- 不要把 Trace 简单退化成原始事件流水。
- 不要把 Transcript 作为主要展示内容；Transcript 可以作为补全来源和追踪信息。

## 前端开发规范

- 前端使用 Vite + React + TypeScript。
- API 封装在 `frontend/src/api/http.ts`。
- 领域类型在 `frontend/src/types/domain.ts`。
- 页面在 `frontend/src/pages/`。
- 菜单在 `frontend/src/config/navigation.ts`。
- 主要样式在 `frontend/src/styles/brand.css`。
- 新增长耗时操作必须提供：
  - Loading 或遮罩
  - 按钮禁用态
  - 错误提示
  - 成功后刷新或明确反馈
- 新增后端字段后要同步更新前端 TypeScript 类型。
- UI 文案默认使用中文。

## MCP 规范

- MCP SSE 端点：`/mcp/sse`。
- MCP message 端点：`/mcp/message`。
- MCP 工具必须通过 Bearer Token 鉴权。
- MCP 查询结果必须按 Token 所属用户隔离。
- 新增 MCP 工具时，需要在 `docs/PROJECT_FUNCTIONAL_SPEC.md` 记录工具名、用途、参数和权限边界。

## 验证要求

根据修改范围执行必要验证：

- 后端修改后至少运行：`mvn -DskipTests compile`
- 前端修改后至少运行：`cd frontend && npm run build`
- Hook 脚本修改后至少运行：`node --check .github/hooks/send-hook.js`
- 数据库迁移修改后检查 migration 顺序和幂等/兼容性。
- 修改后回复中要说明验证结果。

## 代码风格

- 保持现有目录结构和命名风格。
- 避免无关重构和大面积格式化。
- 优先小步修改，保持变更可验证。
- 日志要有助于排查问题，但不要输出明文 Token、API Key、密码等敏感信息。
- 前端样式应延续当前 `brand.css` 风格。

## 常见联动提醒

- 改 Hook 字段解析：同步更新后端实体、接口返回、前端类型、会话详情展示、说明文档。
- 改摘要逻辑：同步检查模型配置默认优先、system prompt、JSON 解析、向量写入、Loading 状态。
- 改 Token/权限：同步检查 REST、MCP、前端菜单和管理员能力。
- 改数据库：新增 Flyway migration，并更新文档的数据模型章节。
- 改前端页面：检查移动/窄屏布局、空状态、错误提示和按钮禁用态。
