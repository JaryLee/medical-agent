# 医疗研究 Agent 真实 DeepSeek API 完整测试流程方案

> 版本：2.0
>
> 更新日期：2026-07-30（Asia/Shanghai）
>
> 文档状态：已落地、可重复执行
>
> 数据级别：仅允许 `SYNTHETIC_ANONYMOUS / TEST_ONLY`
>
> 最近一次完整执行：2026-07-29，PASS

## 1. 目的与结论边界

本方案用于证明“项目已经接入真实 DeepSeek API，并且真实模型能够在认证后的业务工作流中
运行”，而不是只证明某个 HTTP 请求能够返回 200。

完整通过后，可以得出以下结论：

1. 被测运行实例实际选择 `deepseek` Provider。
2. STEP01、STEP04，以及形成 STEP06/STEP07 输入的第三次研究分析均调用真实 DeepSeek。
3. DeepSeek 失败时步骤显式失败，不切换到 Mock 伪装成功。
4. 登录、首次改密、Cookie 会话、CSRF、角色、医院和课题边界在真实浏览器中有效。
5. 模型输出满足项目版本化 JSON 契约，能够进入人工澄清、方向确认、PECO 和检索式门禁。
6. Token 只在服务端进程内使用，不进入源码、命令行参数、日志、HTTP 响应或测试报告。
7. 模型切换没有破坏默认后端回归、前端检查和当前本地运行健康。

不能根据本方案宣称：

- 系统已经获得医院生产数据出境或供应商使用审批。
- 模型输出已经通过医学、统计学或科研管理专家质量验收。
- 系统可以处理真实患者明细、病历或直接标识符。
- STEP08～STEP18 都由大模型生成。它们按架构继续由科研 API、确定性规则和人工门禁执行。
- 三个合成案例可以代表真实医院科研效果。

## 2. 总体测试模型

完整测试分为六道门，前一道失败时不得把后一道的局部成功当作整体通过。

| 门 | 测试层 | 主要证明 |
| --- | --- | --- |
| G0 | 前置条件与密钥门禁 | 环境可执行，Token 存在但不泄露，隔离端口可用 |
| G1 | 默认确定性回归 | 权限、规则、工作流、文档等既有能力没有回归 |
| G2 | 真实 Java API 契约 | DeepSeek 鉴权、模型名、JSON 模式和项目 Schema 可用 |
| G3 | 前端质量门 | ESLint、TypeScript、Vitest 和生产构建通过 |
| G4 | Playwright 真实业务链 | 真实账号、Cookie/CSRF、医院、课题及 STEP01→STEP07 闭环 |
| G5 | 当前实例运行验收 | 8080 使用 DeepSeek 配置，五项服务 UP，冒烟通过 |

G1 中保留的 Mock 只用于确定性单元回归。G2、G4 和当前 8080 运行实例不得使用 Mock。

## 3. 被测配置

真实模型实例必须同时满足：

```text
MEDICAL_MODEL_MODE=deepseek
MEDICAL_MODEL_EXTERNAL_ENABLED=true
MEDICAL_MODEL_API_KEY_FILE=D:\develop\AIWorkspace\MEDICAL_AGENT\deepseek_token.txt
MEDICAL_MODEL_NAME=deepseek-v4-flash
```

约束如下：

- Key 文件不存在、内容仅为空白或不可读时，测试在任何真实调用前失败。
- 不允许同时设置明文 `MEDICAL_MODEL_API_KEY`。
- 不允许在 DeepSeek 异常后注册或调用 `MockModelRouter`。
- Provider 查询接口必须受认证。
- Provider 查询只允许返回 `provider`、`mode`、`modelName`、`externalEnabled`。
- HTTP 响应不得返回 Base URL、Key 文件路径或 Key 内容。

## 4. 环境与数据隔离

### 4.1 环境矩阵

| 项目 | 当前本地应用 | 真实 API 自动化环境 |
| --- | --- | --- |
| 前端 | 5173 | 4174 |
| 后端 | 8080 | 18080 |
| Profile | `postgres` | `memory` |
| Repository | PostgreSQL | 进程内临时存储 |
| ObjectStorage | MinIO | 内存 |
| 文件扫描 | ClamAV | basic，本用例不上传文件 |
| 模型 | DeepSeek | DeepSeek |
| PubMed 等科研 API | 按当前配置 | Mock/关闭，不产生真实检索 |
| 生命周期 | 保持运行 | 套件结束强制停止 |

### 4.2 Windows JAR 隔离

当前 8080 进程会在 Windows 上锁定其启动 JAR。测试套件不得停止当前服务，也不得覆盖该 JAR。

总控脚本使用独立产物：

```text
backend/target/medical-agent-deepseek-live.jar
```

因此可以在 8080 保持运行时重新编译、打包并启动 18080 测试后端。

### 4.3 数据生命周期

- 测试医院、用户、课题、任务、步骤和事件只存在于 18080 的 memory 进程。
- 测试不会读取或写入当前 PostgreSQL 的业务用户、医院、课题或审计数据。
- 测试不会向当前 MinIO 写对象。
- 18080 退出后全部业务测试数据释放。
- 失败截图、Trace 和日志只允许包含合成数据，并位于 Git 忽略目录。

## 5. 测试身份与权限

### 5.1 自动化用户

| 用户名 | 角色 | 用途 | 数据库 |
| --- | --- | --- | --- |
| `deepseek-platform-admin` | `PLATFORM_ADMIN` | 首次登录、改密、创建合成医院和医生 | 仅 18080 memory |
| `deepseek-doctor` | `DOCTOR`、`HOSPITAL_ADMIN` | 首次登录、改密、创建课题并运行 Agent | 仅 18080 memory |

说明：

- 两个用户名固定，便于在 Trace 中识别测试主体。
- 初始密码和改后密码每次由总控脚本随机生成。
- 密码只通过子进程环境变量传递，不写入测试源码、文档或 Java 命令行。
- 测试结束后环境变量恢复到执行前状态。
- 这些用户不会写入当前 8080 使用的 PostgreSQL。

### 5.2 无业务用户的测试

`DeepSeekApiLiveTest` 直接验证模型适配器和输出契约，不经过业务登录，因此没有医院用户。

### 5.3 动态业务标识

每次 Playwright 运行动态生成：

```text
医院编码：DS-<本次运行时间标识>
课题编码：DS-P-<本次运行时间标识>
医院名称：DeepSeek 合成匿名测试医院
课题名称：真实 DeepSeek 合成匿名工作流
```

动态标识避免并行或重复执行时发生唯一键冲突。

### 5.4 当前 8080 固定人工测试账号

为方便在当前本地页面手工复验，PostgreSQL 中另有一个固定的合成测试账号：

| 医院编码 | 用户名 | 角色 | 首次改密 | 数据域 |
| --- | --- | --- | --- | --- |
| `DEEPSEEK-TEST` | `deepseek-doctor` | `DOCTOR`、`HOSPITAL_ADMIN` | 否 | 当前 8080 / PostgreSQL |

该记录与 18080 memory 套件中的同名用户不是同一用户 ID，也不共享医院、密码或会话。固定密码
不写入仓库文档；配置或重置账号时会撤销旧会话并记录 `LOCAL_TEST_ACCOUNT_UPSERT` 审计。

## 6. 测试数据

### 6.1 合成研究想法

```text
SYNTHETIC_ANONYMOUS：拟使用完全虚构的匿名医院历史数据库，
研究2型糖尿病成年患者使用SGLT2抑制剂与12个月eGFR变化的关联。
```

### 6.2 合成澄清答案

```text
合成匿名答案：虚构成年研究人群，观察期12个月，主要终点为eGFR绝对变化；
具体对照、混杂因素和统计参数需要医生及统计专家确认。
```

### 6.3 禁止使用的数据

- 患者姓名、身份证号、手机号、住院号、门诊号。
- 真实病历、检查结果、处方、影像或基因数据。
- 当前 PostgreSQL 中的医院、用户、课题、审计或文件内容。
- 未经批准的真实医院模板或历史科研材料。
- 任何为了“测试阻断”而准备发送给真实模型的敏感字符串。

敏感内容和 Prompt Injection 的阻断测试必须使用本地计数器或 WireMock 证明零出站，不能把
高风险内容发送到 DeepSeek。

## 7. 前置条件

执行前由总控脚本和测试人员共同确认：

### 7.1 自动检查

- JDK 21：`D:\develop\jdk21\bin\java.exe`
- Maven 3.9.11：`D:\develop\environment\apache-maven-3.9.11\bin\mvn.cmd`
- `npm.cmd`、`npx.cmd`
- `deepseek_token.txt` 存在且去除空白后非空
- 18080、4174 没有监听进程
- 后端 POM、前端依赖和 Playwright Chromium 已安装

### 7.2 人工确认

- 当前输入全部为 `SYNTHETIC_ANONYMOUS`。
- 当前网络允许访问 DeepSeek API。
- DeepSeek 账号余额、限流和模型权限满足本次测试。
- 接受本套件至少产生 4 次真实模型调用：Java 契约 1 次、浏览器工作流 3 次。
- 失败产物可以保留合成数据，但不得包含 Token。

### 7.3 不要求停止的服务

当前 5173、8080、PostgreSQL、MinIO 和 ClamAV 可以保持运行。测试使用独立端口和独立 JAR，
不应打断当前页面。

## 8. 自动化执行流程

执行入口：

```powershell
Set-Location D:\develop\AIWorkspace\MEDICAL_AGENT
.\tools\test-deepseek-live.ps1
```

如 Token 文件移动，可显式指定：

```powershell
.\tools\test-deepseek-live.ps1 -TokenFile 'D:\secure\deepseek_token.txt'
```

### 阶段 0：前置检查

1. 校验 JDK、Maven、npm、npx 和 Token 文件。
2. 只判断 Token 是否为空，不输出正文、长度、前缀或哈希。
3. 检查 18080 和 4174。
4. 保存脚本将要覆盖的环境变量原值。
5. 创建 Git 忽略的运行日志目录。

失败处理：立即停止，不产生真实 API 调用。

### 阶段 1：后端默认全量回归

执行：

```powershell
mvn -f backend/pom.xml test -DskipTests=false
```

覆盖：

- 身份、会话、首次改密、CSRF 和角色。
- 多医院及课题成员隔离。
- Agent 幂等、人工门禁、取消、重试、租约恢复和 SSE。
- 模型契约、安全门禁和非法输出。
- STEP07～STEP18 的确定性规则、文献、方案、统计、引用、STROBE、审核和 DOCX 服务。
- ClamAV、MinIO、PostgreSQL 和公共 API 的条件式测试注册。

通过要求：

- 0 Failure、0 Error。
- 允许环境条件测试明确 Skip。
- `DeepSeekApiLiveTest` 在本阶段必须 Skip，避免默认回归意外出网。

### 阶段 2：真实 DeepSeek Java 契约

总控脚本仅在本阶段设置：

```text
RUN_DEEPSEEK_LIVE_TEST=true
DEEPSEEK_TOKEN_FILE=<Token 文件>
```

执行目标：

```text
DeepSeekApiLiveTest
```

核心断言：

| 编号 | 断言 |
| --- | --- |
| API-01 | 请求真实 `/chat/completions` 成功 |
| API-02 | Provider 为 `deepseek` |
| API-03 | 模型名为 `deepseek-v4-flash` |
| API-04 | 输出解析为 `research-analysis/v1` |
| API-05 | Profile 为 `research-idea-profile/v1` |
| API-06 | 恰好返回 DIR-01～DIR-03 三个方向 |
| API-07 | 研究类型只在允许枚举内 |
| API-08 | 不编造样本量或患者事实 |

失败处理：停止后续浏览器真实调用，保留安全错误信息，不输出 Token。

### 阶段 3：生成独立测试 JAR

执行逻辑：

```powershell
mvn -f backend/pom.xml package -DskipTests `
  -Dmedical.build.final-name=medical-agent-deepseek-live
```

断言：

- `backend/target/medical-agent-deepseek-live.jar` 存在且非空。
- 当前 8080 仍在运行。
- 不覆盖 `medical-agent-0.1.0-SNAPSHOT.jar`。

### 阶段 4：启动隔离后端

18080 使用：

```text
spring.profiles.active=memory
medical.model.mode=deepseek
medical.model.external-enabled=true
medical.model.name=deepseek-v4-flash
medical.file-scan.mode=basic
```

平台管理员初始密码由脚本随机生成并通过环境变量传递。启动后最多等待 60 秒：

```text
GET http://127.0.0.1:18080/actuator/health
```

通过要求：HTTP 200 且 `status=UP`。

### 阶段 5：前端质量门

依次执行：

```powershell
npm.cmd run lint --prefix frontend
npm.cmd run typecheck --prefix frontend
npm.cmd run test --prefix frontend
npm.cmd run build --prefix frontend
```

通过要求：

- ESLint 0 Error。
- TypeScript 0 Error。
- Vitest 0 Failure。
- Vite 构建成功。

Element Plus 主包超过 500 kB 是已记录的构建警告，不等于本套件失败；出现新的编译、类型或
运行错误则必须失败。

### 阶段 6：Playwright 真实认证工作流

Playwright 使用：

```text
前端：http://127.0.0.1:4174
后端代理：http://127.0.0.1:18080
浏览器：Chromium
worker：1
retry：0
单用例超时：180 秒
断言超时：90 秒
```

禁止：

- `page.route()` 拦截业务 API。
- HAR 回放。
- 固定模型响应。
- 连接 8080 作为测试后端。
- 失败后切换 Mock。

业务步骤与断言：

| 序号 | 主体 | 操作 | 必须断言 |
| --- | --- | --- | --- |
| E2E-01 | 平台管理员 | 首次登录 | 显示强制改密页 |
| E2E-02 | 平台管理员 | 修改随机初始密码 | `/change-password` HTTP 成功，显示重新登录 |
| E2E-03 | 平台管理员 | 使用新密码登录 | 显示当前平台管理员 |
| E2E-04 | 平台管理员 | 创建动态合成医院 | 返回医院 ID |
| E2E-05 | 平台管理员 | 创建医生 | 角色为 DOCTOR + HOSPITAL_ADMIN |
| E2E-06 | 医生 | 首次登录并改密 | Cookie、XSRF Header 和会话撤销流程成功 |
| E2E-07 | 医生 | 使用新密码登录 | 工作台可见 |
| E2E-08 | 医生 | 查询模型运行信息 | `deepseek/deepseek-v4-flash/true` |
| E2E-09 | 医生 | 创建动态课题 | 课题表格出现动态编码 |
| E2E-10 | 医生 | 提交合成研究想法 | 后台任务创建 |
| E2E-11 | 系统 | 第一次真实模型调用 | 进入 STEP03，至少一个澄清问题 |
| E2E-12 | 医生 | 填写全部合成答案 | 未填完不得继续 |
| E2E-13 | 系统 | 第二次真实模型调用 | 进入 STEP05，恰好三个方向 |
| E2E-14 | 医生 | 确认第一个方向 | 方向确认成功 |
| E2E-15 | 系统 | 第三次真实模型调用 | 形成研究问题输入 |
| E2E-16 | 系统 | 确定性 PECO/检索策略 | 进入 STEP07，存在 `pubmed-query/v1` |
| E2E-17 | 页面 | 展示门禁提示 | 确认检索式前不会执行真实 PubMed |

模型运行信息还必须断言序列化结果不包含：

```text
apiKey
apiKeyFile
deepseek_token
baseUrl
```

### 阶段 7：汇总与强制清理

无论成功或失败，`finally` 必须执行：

1. 校验目标 PID 的命令行确实包含 18080。
2. 停止隔离 Java 进程。
3. 等待进程退出。
4. 恢复所有被覆盖的环境变量。
5. 确认 18080 和 4174 无监听。
6. 保留必要日志；Trace 仅在失败时保留。

成功摘要必须包含：

```text
Status=PASS
Provider=deepseek
JavaContract=PASS
PlaywrightWorkflow=STEP01 -> STEP03 -> STEP04 -> STEP05 -> STEP06 -> STEP07
MockFallback=DISABLED
TestData=SYNTHETIC_ANONYMOUS
```

## 9. 安全与失败关闭测试矩阵

这些用例主要由默认 Java 回归和 WireMock 完成，避免把危险输入发送到真实 API。

| 编号 | 场景 | 方法 | 预期 |
| --- | --- | --- | --- |
| SEC-01 | 手机号、证件号等敏感标识 | 本地门禁 + 请求计数器 | 外部请求数 0 |
| SEC-02 | 忽略系统规则、泄露 Prompt | 本地门禁 + 请求计数器 | 外部请求数 0 |
| SEC-03 | DeepSeek 非法 JSON | WireMock | 步骤失败，不生成方向 |
| SEC-04 | 401/403 | WireMock | 安全错误，不回显 Token |
| SEC-05 | 429 | WireMock | 按策略处理后显式失败/重试 |
| SEC-06 | 5xx | WireMock | 显式失败，不切换 Mock |
| SEC-07 | 连接/读取超时 | WireMock | 有界超时，任务记录错误 |
| SEC-08 | 空响应 | WireMock | 契约失败 |
| SEC-09 | 超长输入/输出 | 本地边界测试 | 出站前拒绝或解析失败 |
| SEC-10 | Provider 信息匿名访问 | HTTP 测试 | 403 |
| SEC-11 | Provider 信息敏感字段 | HTTP/Playwright | 字段不存在 |
| SEC-12 | 无 CSRF 写请求 | HTTP 测试 | 403 |
| SEC-13 | Axios Cookie/Header 双提交 | Playwright | 改密请求成功 |

## 10. 当前 8080 实例验收

隔离套件通过后，才允许验证或启动当前持久化实例：

```powershell
.\tools\start-local-backend.ps1
.\tools\status-local.ps1
.\tools\smoke-local.ps1
```

通过要求：

| 服务/检查 | 预期 |
| --- | --- |
| 前端 5173 | UP |
| 后端 8080 | UP |
| PostgreSQL 5432 | UP |
| MinIO 9000 | UP |
| ClamAV 3310 | UP |
| 后端聚合健康 | HTTP 200 / UP |
| 健康详情脱敏 | 不返回组件详情 |
| 匿名业务 API | 403 |
| API 文档（本地 profile） | 200 |
| MinIO live | 200 |
| ClamAV PING | PONG |

登录当前页面后，模型标签应显示：

```text
模型：deepseek · deepseek-v4-flash · 真 API
```

当前 8080 和 18080 可以使用相同用户名便于识别，但它们是不同数据域中的独立记录，不共享
用户 ID、医院、密码或会话。

## 11. 证据与产物

| 证据 | 路径 | Git 状态 |
| --- | --- | --- |
| 总控输出 | `artifacts/runtime/deepseek-live-suite*.out.log` | 忽略 |
| 隔离后端日志 | `artifacts/runtime/deepseek-e2e-backend.*.log` | 忽略 |
| Maven 报告 | `backend/target/surefire-reports/` | 忽略 |
| Playwright 失败截图/Trace/视频 | `frontend/test-results/` | 忽略 |
| 前端构建 | `frontend/dist/` | 忽略 |
| 测试 JAR | `backend/target/medical-agent-deepseek-live.jar` | 忽略 |
| 测试方案和执行结论 | 本文档 | 跟踪 |

日志允许记录：

- 阶段编号、PASS/FAIL。
- HTTP 状态。
- 测试类和用例名。
- 动态合成医院/课题编码。
- 合成研究输入和输出。

日志禁止记录：

- DeepSeek Token 正文、前缀、哈希或长度。
- 随机测试密码。
- MinIO、数据库或其他真实凭证。
- 当前 PostgreSQL 中的业务数据。

## 12. 失败判定与排障顺序

### 12.1 前置失败

典型原因：依赖缺失、Token 空、端口占用。

处理：

1. 不启动任何真实调用。
2. 只报告缺失项或占用端口。
3. 不自动终止未知端口进程。

### 12.2 Java 契约失败

按以下顺序检查：

1. 网络和 DNS。
2. DeepSeek 401/403 权限。
3. 429 配额/限流。
4. 5xx 服务状态。
5. 模型名是否可用。
6. JSON 契约变化。

不要在错误输出中打印 Authorization Header。

### 12.3 隔离后端启动失败

检查：

- `deepseek-e2e-backend.err.log`
- 18080 端口归属
- 独立测试 JAR 是否存在
- memory Profile 是否生效
- DeepSeek Bean 的双开关和 Key 文件

### 12.4 Playwright 失败

先看失败步骤，再看：

1. `error-context.md`
2. screenshot
3. Trace 网络请求状态和 Header 名称
4. 隔离后端日志

只允许展示 Header 名称和是否存在，不展示 Cookie、CSRF Token 或会话值。

### 12.5 模型输出波动

模型文本允许变化，但以下结构断言不得放宽：

- Schema 版本。
- 三个方向数量和 ID。
- 研究类型枚举。
- 缺失信息显式标记。
- STEP03、STEP05、STEP07 人工门禁。

不得通过删除结构断言来“修复”模型波动。

## 13. 重跑策略

- 开发排障可以只运行失败测试，但不能作为最终验收证据。
- 修复模型适配器、认证、CSRF、工作流或测试脚本后，必须重新运行完整总控脚本。
- 仅修改 Markdown 文档时，不要求重复产生真实模型调用，但必须执行 Markdown/Git diff 检查。
- 仅修改独立打包逻辑时，至少在 8080 在线状态下验证测试 JAR能够成功生成。
- 最终验收不得使用 Playwright retry 掩盖偶发失败；当前固定 `retry=0`。

## 14. 完整通过标准

必须全部满足：

- G0～G5 全部 PASS。
- 后端默认回归 0 Failure、0 Error。
- 真实 `DeepSeekApiLiveTest` 没有 Skip。
- Playwright 真实用例 1/1 通过。
- 至少覆盖 1 次 Java 契约调用和 3 次浏览器工作流模型调用。
- 页面和接口都证明 Provider=`deepseek`。
- 运行期 Mock 回退关闭。
- 敏感内容/Prompt Injection 阻断证明零出站。
- 前端 lint、typecheck、Vitest、build 全部通过。
- 当前 8080 深度健康和 8 项冒烟通过。
- 18080、4174 在结束后释放。
- Token 和随机测试密码未出现在 Git diff、日志、HTTP 响应、截图或报告。

允许但必须记录：

- Docker 不可用导致的 Testcontainers 条件跳过。
- 没有注册邮箱导致的 PubMed Live 条件跳过。
- 已知前端大包体警告。

任一必要项失败时，结论只能写“未通过/待修复”，不得写“真实大模型完整接入完成”。

## 15. 执行结果记录模板

```text
执行时间：
执行人/自动化主体：
Git commit/工作区状态：
模型：
测试数据级别：

G0 前置检查：
G1 后端默认回归：
G2 Java 真实契约：
G3 前端质量门：
G4 Playwright 真实工作流：
G5 当前实例验收：

后端 Tests / Passed / Skipped / Failed：
Playwright Passed / Failed：
真实模型调用覆盖：
MockFallback：
Token 泄露检查：
隔离端口清理：

已知警告：
失败证据：
最终结论：PASS / FAIL
```

## 16. 已完成的执行记录

### 16.1 2026-07-29 完整执行

| 层次 | 结果 | 证据 |
| --- | --- | --- |
| 后端默认回归 | PASS | 94 个测试，82 个通过、12 个条件跳过、0 失败 |
| DeepSeek Java 实机契约 | PASS | 1/1，Provider=`deepseek` |
| 前端静态与单元测试 | PASS | ESLint、TypeScript、Vitest 1/1、Vite build |
| Playwright 真实工作流 | PASS | 1/1，30.2 秒完成 STEP01→STEP07 |
| 运行模型证明 | PASS | `deepseek / deepseek-v4-flash / true` |
| Mock 回退 | DISABLED | 隔离实例仅注册 DeepSeek |
| 测试数据 | PASS | 全部为 `SYNTHETIC_ANONYMOUS` |
| 当前本地实例 | PASS | 五项服务 UP，8/8 冒烟通过 |
| Token 泄露 | PASS | 源码/文档扫描 0 命中 |
| 隔离清理 | PASS | 18080、4174 已释放 |

### 16.2 本轮发现并修复的问题

1. Vite 子进程未稳定继承动态代理目标，新增专用 `vite.deepseek.config.ts` 固定指向 18080。
2. Spring Security 6 默认 XOR/BREACH CSRF 解析与 Axios Cookie 双提交模式不兼容；
   改用 `CsrfTokenRequestAttributeHandler`，除登录外写接口仍全部校验 CSRF。
3. 旧脚本在 Windows 上可能因 8080 锁定默认 JAR 而无法重新打包；现改用独立测试 JAR。
4. 固定测试密码已改为每次随机生成，并通过环境变量传给后端和 Playwright。
5. 脚本结束后恢复调用前的环境变量，不污染开发终端。
6. 当前 PostgreSQL 已创建固定人工测试账号 `DEEPSEEK-TEST / deepseek-doctor`，角色和登录
   状态经 8080 验证通过；验证会话随后撤销。

## 17. 后续扩展

本方案当前完整覆盖真实模型接入边界到 STEP07。完整项目验收还应按独立套件继续覆盖：

- STEP08 真实 PubMed：需要合法注册的工具邮箱。
- STEP09 ClinicalTrials.gov Live。
- STEP10 Crossref Live。
- STEP11～STEP16 确定性研究、统计、引用和 STROBE 回归。
- STEP17 医学/统计专家真实角色审核。
- STEP18 两套真实脱敏医院模板导出及 LibreOffice 逐页验收。
- 生产 HTTPS/HSTS、反向代理、密钥轮换、告警、备份恢复和容灾演练。
- 5～10 个真实匿名历史课题及专家评分。

这些扩展不得使用当前合成测试的 PASS 结果替代。
