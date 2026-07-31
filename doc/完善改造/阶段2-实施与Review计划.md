# 第二阶段实施与 Review 计划：业务读模型与首批用户流程

- 日期：2026-07-30
- 当前状态：`PASS`
- 前置阶段：P0-B `PASS`
- 执行策略：全部采用 V1.2 推荐默认值
- 运行库边界：当前 V19 运行实例保持不变；V27 只在临时数据库验证

## 1. 目标

第二阶段建立医生可理解的课题工作区事实源，并迁移“研究构想 → 澄清 → 研究方向确认”首批纵向
切片。页面只消费稳定业务状态、阶段、待办和允许动作，不再读取 UUID、`STEP_*` 或任务
`outputJson` 推导流程。

退出时必须达到：

1. 首页、我的课题、待办中心、课题概览、工作区头部和阶段导航可用；
2. `ProjectWorkspaceSummary`、阶段、待办、首批流程详情和动作 API 契约稳定；
3. 普通 V2 响应、URL、SSE 和页面不含内部 UUID、步骤码及模型调用编号；
4. 研究构想提交、澄清和方向确认全部由 V2 动作入口执行并支持幂等与版本冲突；
5. SSE 只通知更新，支持拒旧、断线补发、间隙重同步和重新拉取；
6. 首批领域从 `StageOnePanel.vue`、`platform.ts` 中拆出独立前后端模块；
7. V2 默认关闭、旧工作台固定可回退；
8. 后端、前端、真实 PostgreSQL、两医院、契约、E2E、可访问性和 Bundle 门禁通过；
9. Review 后无遗留 P0/P1。

## 2. 当前问题与代码证据

### 2.1 V2 只是占位壳

- `WorkspaceV2View.vue` 只有 21 行，只展示“读模型尚未完成”的提示；
- 路由和功能开关已具备安全默认值：V2 默认关闭，`/workspace/legacy` 固定回退；
- 没有首页、课题列表、待办、概览、阶段导航或首批流程页面。

### 2.2 后端没有业务读模型

- 代码中不存在 `ProjectWorkspaceSummary`、`TodoItem`、`readModelVersion` 或项目级读模型事件；
- `research_project` 只有标识、名称和技术版本；
- `ai_agent_task` 保存 `current_step`、技术状态、内部 UUID 和完整输入/输出 JSON；
- 现有 `AgentWorkflowService.TaskView` 直接把任务 UUID、`currentStep`、输入和输出 JSON 返回客户端；
- 业务状态和待办如果继续由前端推导，将违反 ADR-0012 和固定约束。

### 2.3 现有 API 与前端职责过重

- `AgentWorkflowController` 全部使用任务 UUID；
- `platform.ts` 约 960 行，同时承载身份、课题、文件、Agent、证据、审核和导出类型/调用；
- `StageOnePanel.vue` 约 2,800 行，包含登录、课题、文件以及 STEP01～STEP18 全部页面；
- 医生界面直接展示 `STEP_*`、英文技术状态和内部标识。

### 2.4 SSE 仍是任务级技术事件

- 当前 SSE 以任务 UUID 订阅，并直接发送 `AgentWorkflowRepository.EventData`；
- 没有公开 `projectKey`、业务读模型版本或 `PROJECT_RESYNC_REQUIRED`；
- 不能作为 V2 项目工作区的一致性协议。

## 3. 范围内事项

### S2-A：V27 读模型基础设施

- 新增项目读模型版本表、项目级更新事件和 V2 命令幂等账本；
- 所有表携带 `hospital_id`，使用组合外键保证医院一致性；
- 存量课题回填初始版本，不修改业务数据；
- Agent 领域事件触发项目读模型版本单调递增，并生成可补发的项目级通知；
- 命令账本保存请求哈希、动作、操作者、版本和结果摘要，拒绝同键不同负载。

### S2-B：后端聚合与稳定字典

- 新建独立 `workspace` 包，建立读模型 Repository、聚合 Service、Controller 和事件流；
- 在后端把技术任务状态映射为课题业务状态、稳定阶段、中文标签、进度、阻塞原因和允许动作；
- 阶段字典固定为研究构想、研究方向、证据、研究设计、方案、统计、质量、内部审核、草案导出；
- 待办 key 与任务 UUID 解耦，路由只使用 `projectKey`；
- 医院管理员可见本院课题，普通用户只在 SQL 中查询其成员课题。

### S2-C：V2 只读与动作 API

- 实现冻结契约中的摘要、阶段、用户待办和课题待办 API；
- 增加我的课题列表和首批流程详情 API；
- 实现统一动作端点：
  `POST /api/research/projects/{projectKey}/actions/{actionCode}`；
- 首批动作包括创建研究构想任务、提交澄清、确认研究方向、取消、重试；
- 强制 `Idempotency-Key`、`If-Match: "rmv-N"`、RBAC、医院隔离和服务端状态校验；
- 不接受 task UUID、候选集 UUID 等内部标识；方向使用当前读模型内的稳定候选 key。

### S2-D：项目级 SSE

- 实现 `GET /api/research/projects/{projectKey}/events`；
- 事件只包含 event id、公开 key、读模型版本、类型和时间；
- `Last-Event-ID` 可补发；事件已过保留窗口或出现间隙时发送 `PROJECT_RESYNC_REQUIRED`；
- 前端只把 SSE 当作重新获取信号，拒绝小于等于当前版本的事件。

### S2-E：首批前端纵向切片

- 新建独立 V2 API 模块、类型、中文字典和状态管理；
- 实现首页/我的课题、待办中心、课题概览和阶段导航；
- 实现研究构想、澄清和研究方向确认组件；
- 使用稳定路由 `/projects/{projectKey}/...`；
- 提供加载、空、失败、冲突、断线和重同步状态；
- 未保存构想/澄清答案使用浏览器本地草稿恢复，并在离开时提示；
- 普通医生页面不得展示 UUID、`STEP_*`、原始错误或英文技术状态。

### S2-F：兼容与渐进启用

- `VITE_WORKSPACE_V2_ENABLED=false` 保持默认；
- `/workspace/legacy` 始终可回退；
- V2 代码只调用新 API，旧 API 不删除；
- 未迁移的证据及后续步骤提供明确“返回旧版继续”入口，不伪造 V2 能力；
- 每个前端路由通过角色和课题访问结果控制，客户端守卫不替代服务端授权。

## 4. 明确不做

- 不在第二阶段迁移文献证据、设计、方案、统计、质量、审核和导出详情；这些属于第三阶段；
- 不实现多模型路由、章节级生成、专家评测或成本看板；这些属于第四阶段；
- 不实现 OCR、知识库或 RAG；这些属于第五阶段；
- 不删除旧工作台、旧 UUID API 或历史任务数据；
- 不迁移当前运行库，不停止当前 V19 应用；
- 不执行 git 操作或对外发布。

## 5. 预计修改文件与模块

后端：

- `db/migration/V27__project_workspace_read_model.sql`；
- 新建 `com.jarylee.medicalagent.workspace` 包；
- 扩展课题和 Agent 仓储的医院/成员范围查询及服务端动作适配；
- 新建 OpenAPI/Schema 或契约测试资源；
- 更新安全配置和异常代码，但保持旧 API 兼容。

前端：

- 新建 `src/api/workspaceV2.ts`、`src/types/workspace.ts`；
- 新建 `src/features/workspace/` 下的状态、字典和组件；
- 扩展 `WorkspaceV2View.vue` 与 V2 子路由；
- 只从旧 `platform.ts`/`StageOnePanel.vue` 移出首批切片，旧工作台行为保持不变；
- 新增 Vitest、Playwright 和可访问性测试。

文档：

- 本计划、实施进度、契约文档、追踪清单和阶段报告持续更新。

## 6. 数据库迁移

V27 采用只增不删的前向兼容迁移：

1. 新增项目读模型游标/版本表；
2. 新增项目级通知事件表；
3. 新增 V2 动作幂等账本；
4. 为新表建立医院组合外键、唯一约束、请求哈希检查和查询索引；
5. 对存量课题回填初始读模型版本；
6. 以数据库触发器把 `ai_agent_event` 转换为项目级“读模型可能变化”事件。

迁移不得读取或改写任务负载，不自动修复历史状态。V19→V27 和空库 V1→V27 都必须在临时
真实 PostgreSQL 中通过。

## 7. API 变更

遵循[页面读模型与 API 契约](阶段0-页面读模型与API契约.md)，新增而不替换旧接口。成功响应
包含 `data` 和 `meta(readModelVersion/asOf/latestEventId)`；错误使用稳定代码和 trace id，
不含内部主键。

首批新增路径：

- `GET /api/research/workspace/projects`
- `GET /api/research/projects/{projectKey}/workspace-summary`
- `GET /api/research/projects/{projectKey}/stages`
- `GET /api/research/todos`
- `GET /api/research/projects/{projectKey}/todos`
- `GET /api/research/projects/{projectKey}/idea-direction`
- `POST /api/research/projects/{projectKey}/actions/{actionCode}`
- `GET /api/research/projects/{projectKey}/events`

## 8. 兼容和回退

- 数据库只新增结构，旧 V19～V26 代码不读取新表；
- 新前端由编译时功能开关控制，默认不进入；
- 发现 V2 故障时关闭开关或进入 `/workspace/legacy`；
- V2 动作调用现有领域服务，不复制第二套业务状态机；
- 发布失败时停止新应用、恢复迁移前备份并启动旧应用，禁止结构跨版本混跑。

## 9. 测试方案

后端：

- 聚合映射、中文字典、进度、待办和允许动作单元测试；
- HTTP 契约测试证明响应不含 UUID、`STEP_*` 和原始 output；
- 动作幂等、同键不同负载、`If-Match` 冲突和 RBAC 测试；
- SSE 版本拒旧、补发、间隙重同步和权限测试；
- 两医院同名课题、伪造 key、非成员和跨院负向测试；
- 空库 V1→V27、V19→V27、V26→V27 和失败关闭测试；
- 后端全量 `verify`，必需本地 PostgreSQL 测试不得跳过。

前端：

- 字典、版本拒旧、草稿恢复、动作状态和路由守卫 Vitest；
- lint、类型检查和生产构建；
- Playwright 覆盖登录、我的课题、待办、构想、澄清、方向确认、SSE 重同步和旧版回退；
- 关键页面键盘操作、可见焦点、语义标题、表单标签和颜色对比检查；
- Bundle 记录并与 P0-B 1.066 MB 基线比较。

## 10. Review 检查项

- 后端是否仍从唯一领域事实源生成状态，而非建立漂移的第二状态机；
- 读模型版本在并发命令和 Worker 更新下是否严格单调；
- 命令账本是否支持并发重放并拒绝同键不同负载；
- SSE 是否只在提交后可见、能补发且不会把内部事件负载泄露给客户端；
- 所有查询是否在 SQL 层包含医院和成员条件；
- 页面是否完全移除 UUID、`STEP_*`、原始 output 和英文技术状态；
- 客户端允许动作是否只是展示，服务端是否重新鉴权和校验状态；
- 草稿恢复是否只保存构想/澄清文本，不保存凭据或敏感模型负载；
- 是否保留完整回退路径且没有破坏旧工作台；
- 是否混入第三阶段以后的范围。

## 11. 阶段退出标准

- S2-A～S2-F 全部完成；
- 无未解决 P0/P1 或阶段阻断；
- 必需测试和数据库迁移测试全部执行并通过；
- V2 普通响应和页面没有内部 UUID/步骤码；
- 首批纵向切片可独立启用、关闭和回退；
- Review、修复复测、实施进度和阶段报告完成。

## 12. 风险和依赖

- 业务映射覆盖 STEP01～STEP18，必须用表驱动测试防止遗漏和状态倒退；
- 数据库触发器只产生“可能变化”信号，业务事实仍由聚合查询生成；
- 项目级 SSE 在多实例下不能只依赖进程内广播，必须以数据库事件补发为正确性基础；
- 旧前端大包拆分可能影响现有 E2E，必须保持 legacy 回归；
- 外部 DeepSeek 不属于首批 UI 门禁；使用确定性夹具验证，不伪造外部服务通过。

## 13. 实施顺序

1. S2-A：V27、Repository 和真实 PG 迁移测试；
2. S2-B：读模型聚合、字典、SQL 成员过滤和单元测试；
3. S2-C：只读/动作 API、幂等、版本冲突和契约测试；
4. S2-D：项目级 SSE、补发和重同步测试；
5. S2-E：前端首页/概览/待办/首批流程、状态与草稿恢复；
6. S2-F：功能开关、路由守卫、回退和 E2E；
7. 全量测试、独立 Review、修复复测、阶段报告和门禁。

## 14. 实施结果（2026-07-30）

S2-A～S2-F 已全部落地：

- V27 新增 `project_workspace_cursor`、`project_read_model_event` 和
  `project_workspace_command`，以医院组合外键、项目游标锁、单调版本和项目级事件作为数据库
  正确性基础；
- 新建独立 `workspace` 后端包，完成稳定阶段字典、摘要/阶段/待办/构想方向聚合、SQL 成员范围
  查询、统一动作适配和项目级 SSE；
- 新增本计划第 7 节列出的全部 V2 端点；命令强制 16～128 字符幂等键和
  `If-Match: "rmv-N"`，同一操作者/医院/课题的键跨动作唯一；
- 首页/我的课题、待办、概览、九阶段导航、研究构想、澄清和方向确认已形成可独立启用的 V2
  纵向切片；草稿恢复在浏览器存储不可用时安全降级；
- V2 路由、响应和医生页面不使用内部 UUID、`STEP_*`、任务原始输出或英文技术状态；
- `VITE_WORKSPACE_V2_ENABLED=false` 仍是默认值，`/workspace/legacy` 保持固定回退，旧页面和旧 API
  未删除。

当前 V19 运行实例未停止、未重启、未迁移。V27 的空库和升级验证全部使用随机临时数据库。

## 15. Review 与修复复测

Review 发现的问题均已修复并加入回归：

1. 内存模式 Worker 领域事件没有推进工作区版本，导致 SSE/摘要可能停留在旧版本（P1）；
2. 两个不同幂等键的并发动作可能同时基于同一版本预留成功（P1）；
3. 幂等唯一范围包含动作代码，允许同一键跨动作复用（P1）；
4. 内存仓储只按项目 UUID 保存游标，未包含医院维度（P1）；
5. VIEWER 写动作错误地返回状态冲突而不是授权拒绝（P1）；
6. SSE 订阅关闭与定时任务建立之间存在清理竞态（P2）；
7. 最新任务同时间戳时缺少稳定次序（P2）；
8. 浏览器拒绝本地存储时草稿恢复会抛错（P2）；
9. 阶段导航缺少对应的语义导航结构（P2）。

修复后再次执行聚焦测试、真实 PostgreSQL 并发/升级测试、后端全量验证、前端单元测试和两组
Playwright。最终无遗留 P0/P1。

## 16. 最终门禁

- 后端：66 个 Surefire 套件、130 项测试，0 失败、0 错误；11 项仅为 Docker/外部服务等条件
  测试，第二阶段要求的本地真实 PostgreSQL 测试均实际执行；
- 迁移：空库 V1→V27、V19→V27、V26→V27 全部通过，包含触发器、事件补发、医院隔离、
  幂等持久化和并发版本冲突；
- 前端：ESLint、TypeScript/生产构建、3 个 Vitest 文件共 11 项测试全部通过；
- E2E：旧工作台 2 项、V2 2 项通过；真实 DeepSeek 外部调用 1 项按独立授权条件跳过；
- Bundle：V2 懒加载块 24.87 kB（gzip 8.82 kB）；公共主块仍为 1,066.77 kB
  （gzip 354.74 kB），与 P0-B 基线相当，作为阶段 3 的 P2 拆包项继续追踪；
- 制品：`medical-agent-stage2-final.jar`，SHA-256
  `11DF1C9FEC0D15504EC430F5D6C61E982AFB0C7A8A1FA8537FFCFCF912F39C52`。

完整阶段结论见[第二阶段阶段报告](阶段2-阶段报告.md)。第二阶段门禁结论为 `PASS`，下一阶段
为第三阶段“其余纵向切片与后端结构提取”。
