# 阶段 0：页面读模型与 API 契约

- 状态：已冻结
- 日期：2026-07-30
- 适用版本：工作台 V2 首个纵向切片
- 相关决策：[ADR-0012](../ADR/ADR-0012-课题状态审核矩阵与科研草案边界.md)

## 1. 设计原则

1. PostgreSQL 中的领域数据是事实源；SSE 只通知“可能有更新”。
2. 页面不读取内部 UUID，不解析 `STEP_*`，不从任务 `outputJson` 推导业务状态。
3. 后端统一返回稳定代码、中文标签、允许动作、阻塞原因、下一动作和路由。
4. 每个响应带单调递增的 `readModelVersion` 和生成时间，前端拒绝回退到旧版本。
5. 兼容 API 只能服务旧工作台，并由功能开关隔离。

## 2. 外部课题标识

`projectKey` 是面向浏览器和普通集成的唯一课题标识：

- 格式：`prj_` + 26 位大写 Crockford Base32；
- 熵：至少 128 bit，使用密码学安全随机数；
- 创建后不可更改，不从医院、用户、时间戳或内部 UUID 推导；
- 数据库中全局唯一；
- 常规页面 URL、JSON、审计展示和 SSE 均只暴露 `projectKey`；
- 内部 UUID 仅可在受控运维接口和数据库关联中使用。

非法格式与不存在的 key 均返回相同的 `404 PROJECT_NOT_FOUND`，避免枚举差异。

## 3. 通用响应约定

成功响应：

```json
{
  "data": {},
  "meta": {
    "readModelVersion": 42,
    "asOf": "2026-07-30T05:00:00Z",
    "latestEventId": 891
  }
}
```

错误响应：

```json
{
  "code": "PROJECT_ACTION_NOT_ALLOWED",
  "message": "当前课题正在等待统计审核，不能导出科研草案。",
  "traceId": "01J...",
  "details": {
    "action": "EXPORT_RESEARCH_DRAFT",
    "blockedReasonCodes": ["STATISTICAL_REVIEW_PENDING"]
  }
}
```

错误不得包含内部 UUID、SQL、堆栈、原始模型负载或患者相关内容。

## 4. 核心读模型

### 4.1 ProjectWorkspaceSummary

```json
{
  "projectKey": "prj_6H7M6PR8K2WQ9T5X3N4C1J0BFA",
  "displayName": "匿名高血压随访课题",
  "businessStatus": {
    "code": "IN_PROGRESS",
    "label": "编制中"
  },
  "currentStage": {
    "code": "RESEARCH_DIRECTION",
    "label": "研究方向",
    "status": "WAITING_USER"
  },
  "progress": {
    "completed": 1,
    "total": 9,
    "percent": 11
  },
  "nextAction": {
    "code": "CONFIRM_RESEARCH_DIRECTION",
    "label": "确认研究方向",
    "targetRoute": "/projects/prj_6H7M6PR8K2WQ9T5X3N4C1J0BFA/direction"
  },
  "allowedActions": [],
  "blockedReasons": [],
  "pendingTodoCount": 1,
  "lastUpdatedAt": "2026-07-30T05:00:00Z"
}
```

`progress.percent` 由后端根据已冻结阶段权重计算，必须为 0～100 的整数；前端不得自行重算。

### 4.2 AllowedAction

```json
{
  "code": "EXPORT_RESEARCH_DRAFT",
  "label": "导出科研草案",
  "enabled": false,
  "reasonCode": "STATISTICAL_REVIEW_PENDING",
  "reason": "统计审核尚未通过。",
  "targetRoute": "/projects/{projectKey}/export"
}
```

未授权动作不应仅依赖 `enabled=false`；执行端点仍须重新鉴权、校验医院隔离、状态和内容哈希。

### 4.3 ProjectStage

```json
{
  "code": "STATISTICS",
  "label": "统计分析",
  "status": "BLOCKED",
  "summary": "需要补充主要结局指标参数。",
  "targetRoute": "/projects/{projectKey}/statistics",
  "blockedReasonCodes": ["SAMPLE_SIZE_PARAMETER_MISSING"],
  "completedAt": null
}
```

### 4.4 TodoItem

```json
{
  "todoKey": "todo_01K...",
  "projectKey": "prj_6H7M6PR8K2WQ9T5X3N4C1J0BFA",
  "todoType": {
    "code": "CONFIRM_RESEARCH_DIRECTION",
    "label": "确认研究方向"
  },
  "title": "请选择一个研究方向",
  "description": "候选方向已生成，确认后继续证据检索。",
  "assigneeRole": "PROJECT_OWNER",
  "targetRoute": "/projects/prj_6H7M6PR8K2WQ9T5X3N4C1J0BFA/direction",
  "dueAt": null,
  "status": "OPEN"
}
```

`todoKey` 与具体任务/审核主键解耦。待办状态只开放 `OPEN`、`DONE`、`CANCELLED`。

## 5. 只读 API

| 方法与路径 | 用途 | 权限 |
| --- | --- | --- |
| `GET /api/research/workspace/projects` | 我的课题与首页列表 | 已登录用户（管理员本院、普通用户成员范围） |
| `GET /api/research/projects/{projectKey}/workspace-summary` | 工作台头部、状态、下一动作 | 课题成员 |
| `GET /api/research/projects/{projectKey}/stages` | 阶段导航 | 课题成员 |
| `GET /api/research/todos?status=OPEN&limit=50&cursor=...` | 当前用户待办 | 已登录用户 |
| `GET /api/research/projects/{projectKey}/todos?status=OPEN` | 课题待办 | 课题成员 |
| `GET /api/research/projects/{projectKey}/idea-direction` | 构想、澄清和方向确认详情 | 课题成员 |

列表使用不透明游标，默认 50、最大 100。排序固定为：到期时间升序（空值最后）、创建时间升序、
`todoKey` 升序。

医院隔离必须在数据库查询条件中包含登录用户的 `hospital_id`，不能查询后再过滤。无课题权限时
统一返回 404。

## 6. 命令 API

首个切片只约定动作入口，不允许通用“修改状态”接口：

```http
POST /api/research/projects/{projectKey}/actions/{actionCode}
Idempotency-Key: <UUID 或 16～128 字符稳定键>
If-Match: "rmv-42"
Content-Type: application/json
```

成功返回最新 `ProjectWorkspaceSummary`。幂等键在相同用户、医院和课题范围内跨动作唯一；相同
动作和请求重放复用原结果，同一键用于不同动作或不同负载返回
`409 IDEMPOTENCY_KEY_REUSED`。读模型版本冲突返回
`409 READ_MODEL_VERSION_CONFLICT`，并附最新摘要链接。

服务端只接受 `allowedActions` 定义过的动作代码；仍需逐次做 RBAC、医院隔离、状态转换和内容哈希
校验。

## 7. SSE 与一致性

```http
GET /api/research/projects/{projectKey}/events
Last-Event-ID: 890
```

事件只返回：

```json
{
  "eventId": 891,
  "type": "PROJECT_READ_MODEL_CHANGED",
  "projectKey": "prj_...",
  "readModelVersion": 42,
  "occurredAt": "2026-07-30T05:00:00Z"
}
```

- 收到事件后重新获取摘要或对应资源；事件负载不作为页面事实源。
- `readModelVersion` 小于等于页面当前值时忽略。
- 断线后带 `Last-Event-ID` 重连；服务端无法补齐事件或检测到间隙时发送
  `PROJECT_RESYNC_REQUIRED`。
- 页面重连、从后台恢复和执行命令后都必须重新读取摘要。

## 8. 兼容与功能开关

- `VITE_WORKSPACE_V2_ENABLED=false`：默认关闭 V2 导航。
- `VITE_LEGACY_WORKSPACE_ENABLED=true`：默认保留旧工作台。
- `/workspace/legacy` 是固定回退入口；V2 未达到阶段门禁前 `/workspace` 仍指向旧工作台。
- 既有以 UUID 为路径参数的 API 标记为 compatibility-only；课题详情固定迁移到
  `/api/research/projects/legacy/{id}`，V2 不得调用任何 legacy UUID 入口。
- 只有当某纵向切片通过后端、前端、真实 PostgreSQL、E2E 和回退测试后，才可按环境开启。

## 9. 契约验收

- OpenAPI 与 JSON Schema 能验证全部示例；
- 响应中不存在内部 UUID 和原始 `STEP_*`；
- 两医院同名/同 key 探测均不能越权；
- 动作重放、版本冲突、SSE 断线补发均有真实 PostgreSQL 测试；
- V2 关闭时旧工作台可用，V2 失败时可直接返回 `/workspace/legacy`。

## 10. P0-B 落地边界（2026-07-30）

P0-B 已完成：

- `projectKey` 的格式、随机生成、存量回填、全局唯一和不可变约束；
- `GET /api/research/projects/{projectKey}` 的医院范围解析及不含内部 UUID 的公开视图；
- 非法、未知、跨医院、非成员统一 404；
- UUID 详情查询迁移为 `GET /api/research/projects/legacy/{id}`；
- V24～V26 的身份规范化、医院组合外键和三方审核门禁。

尚未迁移的旧工作台命令、任务、文件、审核和导出接口仍属于 compatibility-only，可能继续使用
UUID。它们不构成 V2 契约，必须由 `/workspace/legacy` 隔离；阶段 2 和阶段 3 的任何新页面不得
调用这些入口。

P0-B 的三方审核兼容接口请求已经增加：

```json
{
  "responsibility": "MEDICAL_REVIEW",
  "decision": "APPROVE",
  "summary": "医学审核通过。",
  "expectedVersion": 1
}
```

`responsibility` 只允许 `MEDICAL_REVIEW`、`STATISTICAL_REVIEW`。响应包含
`reviewRoundNo`、医学/统计各自的审核人、决定、总结、时间、负责人确认、锁定状态、版本、
批注和不可变历史。两类专家通过前禁止负责人确认；语义内容哈希变化后旧轮次不能继续决定或
导出。

阶段 2 必须在这些事实源之上建立稳定业务读模型和动作适配器，不得把兼容审核响应或
`agentTask.outputJson` 直接交给医生页面推导按钮权限。

## 11. 第二阶段落地结果（2026-07-30）

本契约的首个纵向切片已经落地并通过门禁：

- V27 建立项目读模型游标、项目级事件和动作幂等账本；
- 第 5 节六个只读端点、第 6 节统一动作端点和第 7 节项目级 SSE 均已实现；
- 阶段字典实际固定为九项：研究构想、研究方向、证据、研究设计、方案、统计、质量、内部审核、
  草案导出；
- 命令强制 16～128 字符幂等键和 `If-Match`；并发预留在项目游标行上串行化；
- V2 页面、普通响应和 SSE 已由契约测试/静态检查证明不含 UUID、`STEP_*` 和原始任务输出；
- V2 默认关闭并保持 `/workspace/legacy` 固定回退。

现有全局异常包装继续使用统一 `ApiResponse` 外壳，实际错误响应形态为：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "READ_MODEL_VERSION_CONFLICT",
    "message": "课题状态已更新，请刷新后重试。",
    "details": null
  },
  "timestamp": "2026-07-30T08:00:00Z",
  "traceId": "01J..."
}
```

错误代码和不泄露边界仍以第 3 节为准；该外壳属于既有兼容约束，不在第二阶段另建第二套响应
协议。完整测试与 Review 证据见[第二阶段阶段报告](阶段2-阶段报告.md)。
