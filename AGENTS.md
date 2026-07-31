# MEDICAL_AGENT 协作规则

## 目标与优先级

本仓库首先服务于匿名医疗科研课题技术试点。实现与评审按以下顺序取舍：

1. 医疗与统计结论可追溯，不能把模型生成内容表述为已获伦理、科研管理或临床批准。
2. 医院数据隔离、敏感数据外发阻断、认证授权和审计不能因便利性降级。
3. PostgreSQL 是运行事实源；SSE、前端缓存和模型输出都不能替代服务端聚合状态。
4. 任务必须支持多实例安全、幂等、租约丢失后的 fencing 和崩溃恢复。
5. 先完成可验证的纵向切片，再扩展页面、模型或集成范围。

## 当前产品边界

- 首个试点只开放横断面、队列、病例对照三类观察性研究。
- 导出物只能称为“科研草案”，必须带有：
  `仅供科研设计讨论，未经伦理和科研管理审批`。
- 医学审核、统计审核、课题负责人确认必须分别完成。伦理和科研管理审批在系统外完成。
- 禁止使用真实患者数据或直接标识信息进行开发、演示和自动化测试。
- 外部模型默认关闭；没有医院数据政策和生产使用审批时不得开启。

## 变更纪律

- 数据库结构只通过追加 Flyway 迁移演进；不得修改已经发布的 V1～V19。
- 对已有数据先只读扫描并形成报告；未得到课题负责人明确批准，不自动删除、合并或重编号。
- 旧工作台按功能开关逐切片迁移，保留回退入口；不得一次性替换。
- 对外使用不可枚举的 `projectKey`，内部 UUID 不进入常规页面 URL 或响应。
- 不得通过前端解析 `STEP_*` 或任务 JSON 推导业务状态；状态、下一动作和阻塞原因由后端聚合。
- 不得把跳过的真实 PostgreSQL、迁移、隔离或并发测试计为通过。
- 保留用户已有的无关改动；不执行破坏性 Git 操作。

## 默认验证命令

PowerShell 下先指定 JDK 21：

```powershell
$env:JAVA_HOME='D:\develop\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

后端默认门禁：

```powershell
cd backend
mvn.cmd '--batch-mode' '-Dmedical.build.final-name=medical-agent-verify' 'verify'
```

真实 PostgreSQL 门禁：

```powershell
cd backend
mvn.cmd '--batch-mode' '-DlivePostgres=true' 'test'
mvn.cmd '--batch-mode' '-DlivePostgresFlyway=true' '-Dtest=LocalPostgresFlywayLiveTest' 'test'
```

前端门禁：

```powershell
cd frontend
npm run lint
npm run typecheck
npm run test
npm run build
npm audit --audit-level=high
npx playwright test
```

本地运行冒烟：

```powershell
powershell -ExecutionPolicy Bypass -File tools/smoke-local.ps1
```

运行中的默认 JAR 在 Windows 上可能被锁定，验证构建应使用唯一的
`medical.build.final-name`，不得以跳过 `package` 规避完整构建。

## 分阶段门禁

每一阶段都必须按“检查 → 实现 → 测试 → 独立复核 → 修复/复测 → 门禁结论”执行。
阶段报告至少记录：

- 变更范围和未改范围；
- 自动化测试的通过、失败和跳过数量；
- 真实 PostgreSQL/迁移/隔离/并发证据；
- 已知风险、回退路径和后续依赖；
- 明确的 `PASS` 或 `BLOCKED` 结论。

出现 P0/P1 缺陷、必需测试被跳过、数据隔离未证明或回退路径不可用时，不得进入下一阶段。

