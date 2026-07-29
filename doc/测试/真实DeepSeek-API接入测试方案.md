# 医疗研究 Agent 真实 DeepSeek API 接入测试方案

> 版本：1.1（已执行）
>
> 日期：2026-07-29
>
> 数据级别：仅允许 `SYNTHETIC_ANONYMOUS / TEST_ONLY`

## 1. 测试目标

本方案验证项目从确定性 Mock 模型切换到真实 DeepSeek API 后，以下事实同时成立：

1. 当前运行实例实际选择 `deepseek` Provider，不存在运行期 Mock 回退。
2. API Key 只从未跟踪的令牌文件读取，不出现在源码、命令输出、日志、HTTP 响应或测试报告。
3. 真实 `/chat/completions` 返回值满足项目 `research-analysis/v1` 契约。
4. STEP01、STEP04 和形成 STEP06/STEP07 输入的第三次分析均真实经过 DeepSeek。
5. 澄清人工门禁、三个方向约束、PECO 和检索策略仍按工作流运行。
6. 敏感信息和 Prompt Injection 在出站前阻断，不能通过失败后改用 Mock 伪装成功。
7. DeepSeek 超时、鉴权失败、限流、空响应或非法 JSON 会使步骤显式失败。
8. 现有确定性规则、权限、文件、数据库和文档功能不因模型切换回归。

## 2. 非目标

- 不使用真实患者信息、医院内部病历、住院号、手机号或其他可识别数据。
- 不把 DeepSeek 用于替代专家审核、统计确认或科研管理审批。
- 不要求 STEP08～STEP18 全部改成大模型；这些步骤按架构继续使用科研 API、确定性规则和人工门禁。
- 本轮不启用真实 PubMed，避免把“真实大模型测试”和“科研 API 联机测试”混成一个故障域。
- 不执行模型质量排名，也不把三个合成案例代表为医院真实效果。

## 3. 被测配置

真实模型运行必须同时满足：

```text
MEDICAL_MODEL_MODE=deepseek
MEDICAL_MODEL_EXTERNAL_ENABLED=true
MEDICAL_MODEL_API_KEY_FILE=D:\develop\AIWorkspace\MEDICAL_AGENT\deepseek_token.txt
MEDICAL_MODEL_NAME=deepseek-v4-flash
```

缺少任一必要条件时应用启动失败。测试不得设置 `MEDICAL_MODEL_MODE=mock`，不得注册
`MockModelRouter`，也不得在 DeepSeek 调用失败后生成 Mock 结果。

为了让页面和自动化测试可证明当前 Provider，后端提供受认证的只读运行信息接口，只返回
Provider、逻辑模式、模型名和外部调用开关，不返回 Base URL、Key 路径或 Key 内容。

## 4. 测试环境隔离

| 项目 | 当前应用 | 真实 API 测试环境 |
| --- | --- | --- |
| 后端端口 | 8080 | 18080 |
| 前端端口 | 5173 | 4174 |
| 数据 Profile | postgres | memory |
| 大模型 | 切换后为 DeepSeek | DeepSeek |
| 业务数据 | 本地集成数据 | 进程内临时合成数据 |
| 测试结束 | 保持运行 | 强制停止独立进程 |

独立环境使用一次性平台管理员、医院、医生、课题和 Agent 任务。进程退出后内存数据消失，
不写当前 PostgreSQL 或 MinIO。令牌文件由测试启动脚本注入环境变量，脚本只检查存在性和非空，
不得输出内容。

## 5. 测试数据

允许的研究想法：

```text
SYNTHETIC_ANONYMOUS：拟使用完全虚构的匿名医院历史数据库，
研究2型糖尿病成年患者使用SGLT2抑制剂与12个月eGFR变化的关联。
```

澄清答案只使用：

- 虚构匿名成年研究人群；
- 观察期 12 个月；
- 主要终点为 eGFR 绝对变化；
- 暴露组为虚构的 SGLT2 抑制剂使用记录；
- 对照和混杂因素均标记为需要医生/统计专家确认。

禁止把当前数据库中的用户、医院、审计或课题数据拼接到 Prompt。

## 6. 测试层次与用例

### 6.1 静态配置门禁

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| CFG-01 | 本地启动脚本 | 显式设置 DeepSeek 模式、外部开关和 Key 文件 |
| CFG-02 | Key 文件不存在/为空 | 启动失败 |
| CFG-03 | 外部开关为 false | 启动失败 |
| CFG-04 | Provider 查询 | 返回 `deepseek`，不包含 Key、Key 路径或 Base URL |
| CFG-05 | 运行配置搜索 | 没有失败后切换 Mock 的代码路径 |

### 6.2 Java 真实 API 契约

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| API-01 | 合成研究想法调用 | HTTP 成功并解析为 `research-analysis/v1` |
| API-02 | 研究档案 | `research-idea-profile/v1`，关键字段非空 |
| API-03 | 方向数量 | 恰好 3 个，ID 为 DIR-01～03 |
| API-04 | 研究类型 | 仅 CROSS_SECTIONAL/COHORT/CASE_CONTROL |
| API-05 | 未知信息 | 明确待确认，不编造样本量和患者事实 |
| API-06 | Provider | `deepseek` |

该测试由 `RUN_DEEPSEEK_LIVE_TEST=true` 显式开启，默认构建不产生外部调用。

### 6.3 安全与失败关闭

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| SEC-01 | 包含手机号/证件号等敏感标识 | 本地阻断，外部请求次数为 0 |
| SEC-02 | 要求忽略系统规则/泄露提示词 | 本地阻断，外部请求次数为 0 |
| SEC-03 | 非法 JSON | 显式失败，不生成方向 |
| SEC-04 | 401/403 | 只返回安全错误码/HTTP 状态，不回显 Token |
| SEC-05 | 429/5xx/超时 | 步骤失败，可人工重试，不切换 Mock |
| SEC-06 | 输出达到长度上限 | 显式失败 |

SEC-01～02 使用 WireMock 或本地计数器证明零出站；不得为验证阻断而把敏感内容发送给真实 API。

### 6.4 Playwright 真实端到端

Playwright 连接独立 4174/18080 环境，不拦截 `/api`，按真实 UI 和 Cookie/CSRF 流程执行：

1. 平台管理员首次登录、修改初始密码并重新登录。
2. 创建合成测试医院和医生账号。
3. 医生首次登录、修改密码并重新登录。
4. 查询运行信息，断言 Provider=`deepseek`、外部调用开启。
5. 创建课题并进入成员/文件/Agent 工作区。
6. 提交合成研究想法。
7. 等待真实 DeepSeek 第一次调用，进入 STEP03 澄清门禁。
8. 填写全部合成澄清答案并提交。
9. 等待真实 DeepSeek 第二次调用，检查恰好三个研究方向。
10. 选择第一个方向。
11. 等待真实 DeepSeek 第三次调用和确定性 PECO/检索策略生成。
12. 断言任务进入 `STEP_07_BUILD_SEARCH_STRATEGY`，页面存在 `pubmed-query/v1`。

测试禁止 `page.route()`、HAR 回放或固定 API 响应。浏览器控制台、失败截图和 Trace 不得包含
Token；失败产物仍只包含合成数据。

### 6.5 回归测试

- 后端默认测试全量通过；其中 Mock 单元测试仅用于算法确定性回归，不代表运行实例使用 Mock。
- 前端 ESLint、TypeScript、Vitest 和生产构建通过。
- 既有 Playwright Mock 流程可以保留为快速 UI 回归，但真实 DeepSeek 套件必须单独通过。
- 当前 8080 实例切换后执行健康检查和匿名访问保护冒烟。

## 7. 测试执行脚本

已实现：

```text
tools/test-deepseek-live.ps1
frontend/playwright.deepseek.config.ts
frontend/e2e/deepseek-live.spec.ts
```

总控脚本职责：

1. 检查 JDK、Maven、Node、令牌文件和端口。
2. 不读取或打印令牌正文，只把文件路径传给子进程。
3. 构建后端。
4. 执行 Java 真实 API 契约测试。
5. 在 18080 启动 memory + DeepSeek 独立后端并等待健康。
6. 在 4174 启动 Vite，通过可配置代理指向 18080。
7. 运行专用 Playwright 用例。
8. 无论成功或失败都停止独立后端，保留必要的合成测试日志。
9. 汇总每层 PASS/FAIL 和真实调用覆盖点。

## 8. 通过标准

必须全部满足：

- Java 真实 API 契约测试通过。
- Playwright 真实端到端测试通过且至少完成三次 DeepSeek 调用路径。
- Provider 接口和页面明确显示 `deepseek`，不存在 Mock 回退。
- 安全阻断测试证明敏感/注入输入零出站。
- 默认后端和前端回归无新增失败。
- 当前 8080 实例使用 DeepSeek 启动并健康，页面可正常加载。
- Token 未出现在 Git diff、日志、HTTP 响应、截图和测试报告。

任一项失败时，不得宣称“真实大模型完整接入完成”。

## 9. 退出与清理

- 独立 18080 后端和 4174 前端必须停止。
- memory 测试数据随进程释放。
- 不删除 `deepseek_token.txt`，不修改其内容。
- 测试报告可保留；若失败 Trace 含意外敏感信息必须立即隔离并删除。
- 当前 8080 实例只在全套测试通过后切换到 DeepSeek。

## 10. 2026-07-29 执行结果

执行命令：

```powershell
.\tools\test-deepseek-live.ps1
```

| 层次 | 结果 | 证据 |
| --- | --- | --- |
| 后端默认回归 | PASS | 94 个测试，82 个通过、12 个环境条件跳过、0 失败 |
| DeepSeek Java 实机契约 | PASS | `DeepSeekApiLiveTest` 真实请求成功，Provider=`deepseek` |
| 前端静态与单元测试 | PASS | ESLint、TypeScript、Vitest 1/1、Vite 生产构建 |
| Playwright 真实工作流 | PASS | 1/1，30.2 秒完成 STEP01→STEP03→STEP04→STEP05→STEP06→STEP07 |
| 运行模型证明 | PASS | 页面和受认证接口均返回 `deepseek / deepseek-v4-flash / externalEnabled=true` |
| Mock 回退 | DISABLED | 专用后端仅注册 DeepSeek，调用失败显式失败 |
| 测试数据 | PASS | 全部为 `SYNTHETIC_ANONYMOUS`，memory 进程退出后释放 |
| 当前本地实例 | PASS | 8080 按 DeepSeek 配置启动，五项服务 UP，8/8 冒烟通过 |

首次浏览器执行同时发现 Spring Security 6 默认 XOR CSRF 解析与 Axios Cookie 令牌模式不兼容，
表现为真实 `change-password` 请求携带同值 Cookie/Header 后仍返回 403。后端现使用
`CsrfTokenRequestAttributeHandler` 接收 SPA 的 Cookie 双提交令牌，仍保留除登录外全部写接口的
CSRF 防护；修复后完整套件通过。

测试日志只记录阶段、HTTP 状态和合成数据，不输出 DeepSeek Token。独立 18080/4174 进程在
套件结束后已清理。
