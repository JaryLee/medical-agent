# 医疗研究 Agent 项目完善改造方案

> 文档版本：V1.2  
> 编制日期：2026-07-30  
> 修订日期：2026-07-30  
> 评估对象：`medical-agent` 项目 `main` 分支  
> 适用对象：项目负责人、产品经理、前端开发、后端开发、测试人员、Codex Code、医学科研专家、安全人员

---

# 0. 修订说明

V1.1 在 V1.0 基础上完成以下补充和纠正：

- 根据当前代码核验结果，区分“已实现能力、需要回归验证的能力、尚未实现的能力”；
- 将 Agent 租约、重复执行、步骤幂等和旧 Worker 回写风险提升为第一阶段事项；
- 增加课题业务状态、阶段进度、下一步行动、待办和允许操作等后端读模型；
- 明确当前审核流程只有一个通用专家决策，与目标医学、统计、负责人审核模型不一致；
- 纠正方案中的任务状态、系统角色和文件状态与当前代码不一致的问题；
- 将前端改造调整为按业务纵向切片迁移，避免一次性重写；
- 补充真实模型调用审计的可复现性、事务和敏感数据要求；
- 增加执行前必须确认的产品、审核、数据和部署决策。

V1.2 确认第 18 章全部采用推荐默认值，后续执行不再将这些事项视为未决问题；如需改变，必须由项目负责人明确提出并更新本文档。

---

# 1. 文档目的

本方案用于指导现有医疗研究 Agent 项目从“功能纵向跑通的原型”升级为：

- 流程清晰；
- 页面职责明确；
- 医生使用过程有持续引导；
- 前后端结构可维护；
- 状态和专业概念使用中文友好展示；
- 模型、文献、工具和人工审核过程可追溯；
- 满足匿名科研数据试点所需安全基线；
- 为后续知识库、OCR、模型路由和正式试运行预留扩展空间。

本次改造重点不是继续堆叠新功能，而是优先解决以下问题：

1. 当前前端将整个科研流程集中在一个页面中，用户难以判断当前阶段和下一步操作。
2. `StageOnePanel.vue` 和 `platform.ts` 体积过大，页面、流程、接口和状态高度耦合。
3. 页面直接展示 `STEP_12`、`DIR-02`、`OWNER`、`QUEUED`、UUID、SHA-256 等技术编码，医生理解成本高。
4. 后端存在流程正确性、模型调用审计、租户约束、数据库唯一性等高优先级问题。
5. 文档解析、模型路由、Spring AI、RAG 仍处于基础或预留阶段。
6. 项目已经完成较多纵向功能，下一阶段应从“能运行”转向“好使用、可维护、可审计、可试点”。
7. Agent 任务租约短于部分外部调用最长耗时，缺少续租和执行令牌，存在重复执行及旧 Worker 回写风险。
8. 当前课题模型没有可直接支撑首页、待办和阶段导航的业务状态读模型。
9. 当前只有一个通用专家审核决定，尚不能表达医学、统计、伦理和课题负责人各自的确认责任。

---

# 2. 当前项目总体评价

## 2.1 当前完成度

现有项目已经具备较完整的科研课题生成主链：

```text
医生录入研究想法
→ 系统识别缺失信息
→ 医生补充澄清
→ 生成研究方向
→ 医生确认研究方向
→ 生成研究问题结构
→ 生成并确认检索策略
→ 检索医学文献
→ 检索临床试验注册研究
→ 文献真实性核验
→ 相似研究与研究空白分析
→ 推荐研究设计
→ 生成研究方案草案
→ 生成统计分析计划草案
→ 引用依据核验
→ 报告规范预检查
→ 专家审核
→ 受控 Word 导出
```

现有项目的主要优点：

- 使用持久化任务，而不是一次模型调用完成全流程；
- Agent 任务支持暂停、确认、失败重试和恢复；
- 已接入 PubMed、ClinicalTrials.gov、Crossref 等数据源；
- 文献编号和元数据具有真实性校验意识；
- 已实现多医院隔离、课题成员权限和操作审计；
- 文件上传具备扩展名、MIME、魔数、病毒扫描和敏感信息判断；
- 专家审核、版本锁定和受控 Word 导出边界较清晰；
- 已有数据库迁移、自动化测试、架构决策记录和运维脚本。

当前项目不是简单骨架，而是一个较完整的医疗科研 Agent MVP。

## 2.2 当前主要问题

### 产品层面

- 一个页面承载几乎全部业务；
- 课题状态、任务状态和步骤状态混在一起；
- 用户必须理解系统内部步骤才能操作；
- 页面缺少明确的“下一步”引导；
- 管理功能和课题业务功能混在同一工作台；
- 技术字段和英文状态直接暴露给医生；
- 专家审核、统计审核和课题负责人确认缺少清晰角色视图。

### 前端工程层面

- `StageOnePanel.vue` 约 2800 行；
- `platform.ts` 约 900 行；
- 页面数据、API、表单、权限、Agent 事件和业务流程集中；
- 局部状态和全局状态混杂；
- 服务端状态、会话状态和纯界面状态没有明确区分；
- SSE 事件到达后通过整任务刷新维持状态，缺少统一缓存失效和事件一致性策略；
- 页面组件无法复用；
- 后续继续增加功能会明显提高回归风险。

### 后端层面

- 部分 Agent 步骤实际使用的 Prompt 与记录的 Prompt 不一致；
- 医生确认的研究方向可能在后续步骤中被模型重新生成，产生漂移；
- 当前模型路由名义上有多个逻辑模型，实际仍返回同一模型；
- Spring AI 依赖存在，但主流程仍通过自定义 `RestClient` 调用；
- 模型调用日志不完整；
- `model_call_id` 当前是业务层生成的随机 UUID，不代表真实供应商调用；
- Agent 默认租约短于模型和外部工具可能的最长执行时间，且没有续租和 Fencing Token；
- 任务 `version` 被当作步骤 `attempt` 使用，不能准确表达独立步骤重试次数；
- 任务状态更新、步骤结果和事件发布尚未形成明确的原子提交或 Outbox 边界；
- 课题主表只有编号、名称和乐观锁版本，没有业务阶段、下一步行动和待办读模型；
- 多医院隔离主要依赖应用层，数据库联合约束不足；
- 用户名和医院编码存在大小写唯一性问题；
- 专家审核只支持通用 `EXPERT` 单次决策，不能表达医学和统计分别审核；
- Worker、Repository、DOCX 引擎等类体积过大；
- pgvector 已安装，但实际 RAG 尚未落地。

---

# 3. 改造目标

## 3.1 用户体验目标

改造后的系统应让医生随时明确：

1. 当前正在处理哪个课题；
2. 课题进行到哪一阶段；
3. 当前阶段系统已完成什么；
4. 现在需要补充、确认或审核什么；
5. 下一步应该点击哪里；
6. 哪些内容由模型生成；
7. 哪些内容来自真实文献；
8. 哪些内容仍需医学、统计或伦理专家确认；
9. 最终导出的内容基于哪个审核版本。

## 3.2 技术目标

- 一个页面只承担一个明确业务职责；
- 一个菜单对应一个可理解的科研工作区；
- 页面状态使用中文业务语言；
- 复杂流程通过路由、阶段状态和任务中心管理；
- 页面局部状态与跨页面全局状态分离；
- 服务端状态与界面状态分离，并建立统一缓存、失效和 SSE 重连策略；
- 后端提供课题阶段、下一步、待办、允许操作和不可操作原因等业务读模型；
- 后端 Agent 步骤改为可注册处理器；
- Agent 步骤具备独立尝试号、租约续期、执行令牌和幂等写入；
- 模型、Prompt、文献和人工确认形成完整审计链；
- 数据库承担最终租户一致性约束；
- 安全问题完成分级整改并具备自动化验证。

## 3.3 约束原则

1. 继续采用模块化单体，不拆微服务。
2. 不为了使用 Spring AI 重写已稳定的业务流程。
3. 不允许模型直接生成未经工具验证的 PMID、DOI 或试验注册号。
4. 不允许一次模型请求完成全部研究流程。
5. 医生页面不直接展示数据库 ID、UUID、步骤编码和内部状态码。
6. 不用纯英文缩写作为页面主要标题。
7. 专业缩写首次出现时必须附中文说明。
8. 前端隐藏技术字段不等于删除后端审计字段。
9. 前端权限只改善体验，后端仍必须重新校验。
10. 高风险内容必须保留专家审核。
11. 前端不得通过 `STEP_*` 自行推断业务阶段、下一步操作和按钮权限。
12. 任务完成写入必须校验当前执行令牌，过期 Worker 不得覆盖新结果。
13. 当前第一版完整流程只开放横断面、队列和病例对照研究；其他研究类型在对应规则和质量规范完成前不进入正式入口。
14. “正式版本”和“科研草案”必须有不同的审核门槛、文档标识和导出权限。
15. 文件行数仅作为职责混杂的预警，不作为机械拆分和验收的唯一标准。

## 3.4 已实现、待验证与未实现边界

为避免重复建设，实施前必须按以下三类维护整改清单。

### 已实现，需回归验证

- CSRF Token 和前端统一 XSRF Header；
- Session Cookie 的 `HttpOnly`、`SameSite=Strict`，生产环境 `Secure`；
- 修改密码后撤销旧会话；
- 登录失败次数限制和临时锁定；
- 应用层医院、课题成员和文件访问权限校验；
- 文件扩展名、MIME、魔数、病毒扫描和敏感信息初筛；
- Agent 持久化、暂停确认、失败重试和 SSE 事件回放基础。

### 已部分实现，需补齐

- 多医院隔离：应用层已有，数据库复合外键不足；
- Prompt Injection：已有启发式规则，但不能作为最终安全边界；
- 敏感信息检测：已有正则初筛，但不能等同于完成去标识化；
- 模型路由：有逻辑模型抽象，但实际路由到同一个模型；
- Prompt 版本：存在资源文件和版本号，但实际调用和步骤审计不一致；
- 审核与锁定：已有通用专家审核和负责人确认，但缺少医学、统计等独立结论；
- 方案版本：已有生成版本和锁定基础，但缺少完整手工编辑、章节重生成和版本比较接口。

### 尚未形成完整能力

- Agent 租约续期、执行令牌、旧 Worker 写入隔离和步骤级幂等；
- 真实模型调用日志及可复现审计；
- 课题业务状态、阶段进度、下一步、待办和允许操作读模型；
- 医学专家和统计专家分别审核；
- 前端服务端状态缓存和事件一致性策略；
- 真实 PostgreSQL 迁移和租户复合约束在 CI 中强制执行；
- OCR、结构化文档解析、知识库、混合检索和正式 RAG。

---

# 4. 目标菜单与信息架构

本节描述目标产品形态，不代表所有菜单需要在同一迭代一次性交付。

首页、课题概览和待办中心实施前，后端必须先提供统一业务读模型。前端不得直接根据任务 `currentStep`、任务输出 JSON 或内部状态码推导阶段和下一步。

建议最小读模型：

```text
ProjectWorkspaceSummary
├── projectKey
├── displayName
├── businessStatus
├── currentStage
├── progress
├── nextAction
├── allowedActions
├── blockedReasons
├── pendingTodoCount
└── lastUpdatedAt

TodoItem
├── todoKey
├── projectKey
├── todoType
├── title
├── description
├── assigneeRole
├── targetRoute
├── dueAt
└── status
```

`projectKey` 可以使用公开标识，但普通页面不展示数据库 UUID。

# 4.1 一级菜单

登录后建议使用以下一级菜单：

```text
首页
我的课题
待办中心
科研资源
系统管理
```

其中“系统管理”仅对有权限的用户展示。

## 首页

首页只展示用户最关心的信息：

- 我的进行中课题；
- 当前待处理事项；
- 最近更新课题；
- 最近完成方案；
- 系统提醒；
- 最近动态；
- 快速创建课题入口。

禁止展示：

- 模型供应商内部编码；
- 数据库主键；
- 任务 UUID；
- 原始步骤编码；
- 原始状态码；
- SHA-256；
- 英文角色编码。

## 我的课题

二级分类：

```text
全部课题
我负责的课题
我参与的课题
等待我处理
已完成课题
已归档课题
```

列表建议展示：

| 展示项 | 说明 |
|---|---|
| 课题名称 | 主要识别信息 |
| 研究方向 | 中文名称 |
| 当前阶段 | 如“正在检索医学文献” |
| 我的角色 | 课题负责人、编辑成员、查看成员 |
| 下一步事项 | 如“请确认主要结局” |
| 最近更新 | 友好时间 |
| 操作 | 进入课题、继续处理 |

内部课题编码不应作为第一列。确需展示时改称“课题编号”，放在次要信息中。

## 待办中心

集中展示所有需要当前用户处理的事项：

```text
待补充研究信息
待确认研究方向
待确认检索策略
待确认研究设计
待医学专家审核
待统计专家审核
待课题负责人确认
待处理失败任务
待复核文件
```

医生不需要逐个进入课题查找下一步。

## 科研资源

建议包含：

```text
医学文献检索
临床试验注册研究
研究方法与规范
历史课题
院内知识库
```

第一阶段优先实现：

- 医学文献检索；
- 临床试验注册研究；
- 观察性研究报告规范；
- 本院历史课题相似性查询。

## 系统管理

按权限展示：

```text
用户与权限
医院配置
文档模板
引用格式
Prompt 与模型配置
操作审计
系统运行状态
```

文档模板、引用格式和审计不应继续混在课题研究流程页面中。

---

# 4.2 课题工作区

进入课题后使用独立的课题工作区。

建议左侧菜单：

```text
课题概览
研究构想
文献证据
研究设计
研究方案
统计分析
质量检查
专家审核
成果导出
课题设置
```

顶部固定展示：

- 当前课题名称；
- 当前阶段；
- 整体进度；
- 下一步操作；
- 当前用户角色；
- 返回课题列表。

## 阶段顺序

```text
研究构想
    ↓
文献证据
    ↓
研究设计
    ↓
研究方案
    ↓
统计分析
    ↓
质量检查
    ↓
专家审核
    ↓
成果导出
```

允许查看历史阶段，但是否可编辑由业务状态决定。

例如：

- 未确认研究方向时，文献证据页面显示“等待确认研究方向”；
- 未确认研究设计时，研究方案页面只允许预览草稿结构；
- 未完成质量检查时，专家审核页面展示缺失项；
- 未完成专家审核时，成果导出按钮不可用。

---

# 5. 引导式前端流程设计

# 5.1 新建课题向导

新建课题不应只输入编码和名称，应改为四步向导。

## 第一步：课题基本信息

填写：

- 课题名称；
- 所属科室；
- 医学专业方向；
- 课题负责人；
- 计划研究类型；
- 不确定时可选择“暂不确定”。

技术编码由系统自动生成，医生不需要填写无意义编号。

页面按钮：

```text
保存草稿
下一步：填写研究构想
```

## 第二步：研究构想

引导填写：

- 临床或科研现象；
- 想研究的人群；
- 想研究的因素、干预或检查；
- 希望观察的结局；
- 当前已有数据；
- 预计研究时间；
- 可上传已有构想或方案。

页面应明确提示：

> 请勿上传姓名、身份证号、手机号、住院号等可识别患者身份的信息。

按钮：

```text
上一步
保存草稿
让研究助手分析
```

## 第三步：信息完整性检查

Agent 分析后分三栏展示：

```text
已经明确
需要补充
存在冲突或不确定
```

缺失问题使用问答卡片，不使用超长表单。

每个问题包含：

- 问题；
- 为什么需要；
- 示例答案；
- 当前回答；
- 是否必须回答。

按钮：

```text
保存回答
重新分析
下一步：查看研究方向
```

## 第四步：选择研究方向

研究方向使用卡片展示，不显示 `DIR-01`、`DIR-02`。

每张卡片展示：

- 方向名称；
- 适合解决的问题；
- 推荐研究类型；
- 需要的数据；
- 主要优势；
- 主要限制；
- 推荐程度；
- 需要确认的风险。

操作：

```text
选择此方向
查看详细对比
返回补充信息
```

确认弹窗示例：

> 您将基于“糖尿病患者某类药物使用与肾功能变化的关联研究”继续进行文献检索和研究设计。后续仍可退回修改，但历史分析结果会保留在版本记录中。

---

# 5.2 课题概览页

课题概览不承载复杂编辑，只负责导航和状态说明。

## 当前进度

```text
研究构想      已完成
文献证据      进行中
研究设计      未开始
研究方案      未开始
统计分析      未开始
质量检查      未开始
专家审核      未开始
成果导出      未开始
```

## 下一步行动

醒目卡片：

> 当前需要您确认医学文献检索策略。  
> 确认后系统将执行真实 PubMed 文献检索。

按钮：

```text
去确认检索策略
```

## 课题摘要

展示：

- 研究问题；
- 研究对象；
- 暴露因素或干预；
- 对照；
- 主要结局；
- 当前研究类型；
- 当前证据数量；
- 待确认事项。

## 最近动态

中文描述：

```text
李医生确认了研究方向
系统完成了医学文献检索
王医生添加了医学审核意见
统计专家退回了统计分析草案
```

不得直接展示：

```text
PROJECT_UPDATED
STEP_08_COMPLETED
RESOURCE_ID
```

---

# 5.3 研究构想页

建议拆分为四个区域。

## 原始构想

展示：

- 原始文字；
- 当前版本；
- 修改时间；
- 上传材料；
- 编辑入口。

## 信息完整性

以卡片显示：

- 已明确；
- 待补充；
- 存在冲突；
- 不适用于本课题。

## 研究方向

每个方向以中文卡片展示。

英文研究类型映射：

| 内部编码 | 页面中文 |
|---|---|
| CROSS_SECTIONAL | 横断面研究 |
| COHORT | 队列研究 |
| CASE_CONTROL | 病例对照研究 |
| SYSTEMATIC_REVIEW | 系统综述 |
| DIAGNOSTIC_ACCURACY | 诊断准确性研究 |
| RANDOMIZED_CONTROLLED_TRIAL | 随机对照试验 |

## 研究问题结构

页面标题：

> 研究问题结构

副标题：

> 按研究对象、暴露或干预、对照和研究结局进行结构化整理。

字段名称：

| 中文名称 | 可选辅助缩写 |
|---|---|
| 研究对象 | P |
| 暴露因素或干预 | E/I |
| 对照 | C |
| 研究结局 | O |
| 时间范围 | T |
| 研究场景 | S |

禁止只显示 `P/E/C/O`。

---

# 5.4 文献证据页

建议使用四个页签：

```text
检索策略
医学文献
临床试验注册研究
相似研究与研究空白
```

## 检索策略

展示：

- 当前研究问题；
- 关键词和同义词；
- 检索条件；
- PubMed 检索式；
- 系统提示的局限；
- 医生确认记录。

页面标题使用：

> 确认医学文献检索策略

不显示 `STEP_08`。

## 医学文献

列表展示：

- 论文标题；
- 作者；
- 期刊；
- 发表时间；
- 文献类型；
- 课题相关程度；
- 证据范围；
- 元数据是否核验。

外部标识中文化：

| 技术名称 | 页面显示 |
|---|---|
| PMID | PubMed 文献编号 |
| DOI | 数字对象标识 |
| PMCID | PubMed Central 全文编号 |

这些字段放在文献详情中，不作为列表第一列。

## 临床试验注册研究

展示：

- 研究名称；
- 招募状态；
- 研究阶段；
- 研究类型；
- 申办单位；
- 国家或地区；
- 登记时间；
- 与当前课题关系。

状态中文化示例：

| 原始状态 | 中文 |
|---|---|
| RECRUITING | 正在招募 |
| COMPLETED | 已完成 |
| NOT_YET_RECRUITING | 尚未开始招募 |
| ACTIVE_NOT_RECRUITING | 进行中，已停止招募 |
| TERMINATED | 已终止 |
| WITHDRAWN | 已撤回 |

## 相似研究与研究空白

分三块展示：

### 已有相似研究

- 研究主题；
- 研究设计；
- 研究对象；
- 主要结局；
- 与当前课题相似点；
- 主要差异。

### 可能的研究空白

- 人群差异；
- 时间范围差异；
- 指标差异；
- 地区差异；
- 方法学不足；
- 现有证据局限。

### 创新性提醒

不直接写“本课题具有创新性”，改为：

> 系统识别出以下潜在差异点，是否构成创新性仍需课题负责人和专家确认。

---

# 5.5 研究设计页

建议拆为：

```text
设计建议
研究对象
暴露或干预与对照
研究终点
偏倚与混杂
数据可行性
```

## 设计建议

使用对比卡片：

| 项目 | 推荐方案 | 备选方案 |
|---|---|---|
| 研究类型 | 回顾性队列研究 | 病例对照研究 |
| 推荐理由 | 可利用现有历史数据 | 适用于结局较少场景 |
| 所需数据 | 用药、检验、随访 | 病例与对照资料 |
| 主要限制 | 混杂偏倚 | 选择偏倚 |

医生确认后保存完整方案快照，不仅保存编码。

## 研究终点

页面使用“主要结局”和“次要结局”，不要只用 Endpoint。

每个结局展示：

- 中文名称；
- 定义；
- 数据来源；
- 观察时间；
- 单位；
- 是否需要专家确认。

---

# 5.6 研究方案页

研究方案不应是一个超长文本框。

建议按章节展示：

```text
课题名称
研究摘要
研究背景
研究现状
研究空白
研究目标
研究假设
研究设计
研究对象
纳入标准
排除标准
暴露或干预
对照
主要结局
次要结局
变量定义
数据收集
偏倚控制
伦理与数据安全
研究进度
预期成果
参考文献
```

每个章节支持：

- 查看；
- 手工编辑；
- 让研究助手重新生成本章节；
- 查看生成依据；
- 查看引用；
- 查看历史版本；
- 专家批注；
- 锁定已确认章节。

页面必须标记：

```text
系统生成
人工修改
专家确认
待确认
```

---

# 5.7 统计分析页

页面分区：

```text
变量清单
描述性分析
主要结局分析
次要结局分析
混杂因素处理
缺失数据处理
亚组与敏感性分析
样本量参数
统计专家意见
```

禁止让模型直接给出最终样本量。

样本量部分显示：

- 研究设计；
- 需要的参数；
- 当前已提供参数；
- 参数来源；
- 计算结果；
- 计算方法；
- 待统计专家确认。

页面提示：

> 本分析为系统生成的统计方案草案，不能代替统计学专家审核。

---

# 5.8 质量检查页

建议页签：

```text
引用核验
研究一致性
报告规范检查
敏感信息检查
待确认问题
```

## 引用核验

中文状态：

| 内部状态 | 页面中文 |
|---|---|
| VERIFIED | 已核验 |
| PARTIALLY_VERIFIED | 部分核验 |
| UNVERIFIED | 未核验 |
| MISMATCH | 信息不一致 |
| FAILED | 核验失败 |

## 研究一致性

检查：

- 研究目标与主要结局是否一致；
- 研究设计与统计方法是否一致；
- 纳入标准与研究对象是否一致；
- 文献结论是否支持正文；
- 是否将相关性写成因果关系；
- 是否将注册研究当作正式论文；
- 是否将摘要级信息当作全文证据。

## 报告规范检查

页面使用：

> 观察性研究报告规范预检查

首次出现时可以说明：

> STROBE：观察性研究报告规范。

后续页面可使用“观察性研究报告规范”，不直接只显示 STROBE。

---

# 5.9 专家审核页

按角色分栏：

```text
医学专家审核
统计专家审核
课题负责人确认
```

每个审核角色显示：

- 审核状态；
- 审核人；
- 审核时间；
- 审核意见；
- 待修改事项；
- 当前版本。

审核操作中文化：

```text
通过
退回修改
暂不通过
补充意见
```

禁止只显示：

```text
APPROVED
REJECTED
PENDING
```

---

# 5.10 成果导出页

导出前展示检查清单：

```text
研究问题已确认
研究设计已确认
医学专家已审核
统计专家已审核
引用核验已通过
质量检查已完成
课题负责人已确认
```

未满足时显示明确原因。

可导出：

- 课题研究方案；
- 研究摘要；
- 文献证据表；
- 变量字典；
- 统计分析计划；
- 审核意见记录；
- Word 文档；
- Markdown 文档；
- PDF 文档（后续）。

文件名建议：

```text
课题名称_研究方案_V1.0_20260730.docx
```

不要使用纯 UUID 作为用户下载文件名。

---

# 6. 中文展示规范

# 6.1 禁止直接展示的技术字段

以下字段原则上不在普通医生页面直接展示：

```text
UUID
数据库主键 ID
hospital_id
project_id
task_id
step_run_id
resource_id
SHA-256
trace_id
model_call_id
prompt_code
prompt_version
STEP_01
DIR-02
QUEUED
RUNNING
OWNER
EDITOR
VIEWER
```

这些字段仍保留在后台、接口和审计中。

## 仅管理员或技术审计页面可查看

- 请求追踪编号；
- 模型调用编号；
- 文件哈希；
- Prompt 版本；
- 技术错误码；
- 原始第三方响应编号。

普通页面只展示：

```text
课题负责人
编辑成员
查看成员
等待处理
处理中
处理完成
处理失败
```

---

# 6.2 状态中文映射

建议建立统一展示字典，不允许页面各自写字符串。字典必须来自实际领域模型或后端契约，不能由前端自行创造与后端不一致的状态。

每个状态需标明：

```text
当前已存在
目标新增
兼容期映射
是否需要数据库迁移
允许操作
不可操作原因
```

## 课题状态

当前 `research_project` 尚无业务状态字段。以下为目标状态，不是现有数据库编码：

| 内部编码 | 中文 |
|---|---|
| DRAFT | 草稿 |
| IN_PROGRESS | 进行中 |
| WAITING_REVIEW | 等待审核 |
| REVISION_REQUIRED | 需要修改 |
| APPROVED | 已通过 |
| COMPLETED | 已完成 |
| ARCHIVED | 已归档 |
| FAILED | 处理失败 |

## 任务状态

当前 Agent 任务实际状态如下：

| 内部编码 | 中文 |
|---|---|
| QUEUED | 等待处理 |
| RUNNING | 正在处理 |
| WAITING_CONFIRMATION | 等待确认 |
| REVISION_REQUIRED | 需要修改 |
| COMPLETED | 已完成 |
| FAILED | 处理失败 |
| CANCELLED | 已取消 |

如后续引入 `RETRYING`，必须同步定义数据库约束、状态迁移和重试次数，不得只增加页面标签。

## 用户角色

系统角色与课题成员角色必须分开，不应混用一套编码。

### 当前系统角色

| 内部编码 | 中文 |
|---|---|
| DOCTOR | 医生/研究者 |
| EXPERT | 专家 |
| HOSPITAL_ADMIN | 医院管理员 |
| PLATFORM_ADMIN | 平台管理员 |
| AUDIT_ADMIN | 审计管理员 |

### 当前课题成员角色

| 内部编码 | 中文 |
|---|---|
| OWNER | 课题负责人 |
| EDITOR | 编辑成员 |
| VIEWER | 查看成员 |

### 目标审核职责

医学审核、统计审核、伦理确认属于“审核职责或审核任务类型”，不应仅通过前端把通用 `EXPERT` 翻译为不同角色。实施前需决定是新增系统角色、专家资质标签，还是课题级审核指派关系。

## 文件状态

当前文件模型主要使用安全状态和解析状态两组字段：

| 当前内部编码 | 页面中文 |
|---|---|
| SAFE | 安全检查通过 |
| WARNING | 存在风险提示 |
| BLOCKED_FOR_EXTERNAL_MODEL | 禁止发送到外部模型 |
| REQUIRES_ADMIN_REVIEW | 需要管理员复核 |
| EXTRACTED | 文本提取完成 |
| EMPTY | 未提取到有效文本 |

以下为目标文件处理状态。如采用，必须通过数据库和 API 正式建模：

| 内部编码 | 中文 |
|---|---|
| UPLOADED | 已上传 |
| SCANNING | 正在安全检查 |
| SAFE | 安全检查通过 |
| REJECTED | 已拒绝 |
| EXTRACTING | 正在解析 |
| PARSED | 解析完成 |
| REVIEW_REQUIRED | 需要人工复核 |
| FAILED | 处理失败 |

---

# 6.3 专业缩写展示规范

缩写首次出现时展示中文说明：

```text
研究问题结构（PICO）
观察性研究报告规范（STROBE）
随机对照试验报告规范（CONSORT）
系统综述报告规范（PRISMA）
数字对象标识（DOI）
PubMed 文献编号（PMID）
```

后续页面可使用简化名称，但不能仅显示缩写且无说明。

---

# 6.4 错误提示中文化

禁止直接展示：

```text
HTTP 500
NullPointerException
VALIDATION_FAILED
MODEL_TIMEOUT
RESOURCE_NOT_FOUND
```

普通用户提示示例：

| 技术错误 | 用户提示 |
|---|---|
| MODEL_TIMEOUT | 研究助手响应超时，请稍后重试 |
| PUBMED_UNAVAILABLE | 医学文献服务暂时不可用 |
| VALIDATION_FAILED | 部分信息不完整，请按提示补充 |
| PERMISSION_DENIED | 您没有权限执行此操作 |
| FILE_REJECTED | 文件未通过安全检查 |
| CITATION_MISMATCH | 文献信息与引用内容不一致 |
| TASK_LEASE_EXPIRED | 任务处理已中断，系统将重新尝试 |

技术详情写入后台日志和审计页面。

---

# 7. 前端工程拆分方案

# 7.1 目标目录结构

```text
frontend/src
├── api
│   ├── auth.ts
│   ├── dashboard.ts
│   ├── projects.ts
│   ├── projectMembers.ts
│   ├── researchIdeas.ts
│   ├── agentTasks.ts
│   ├── literature.ts
│   ├── studyDesign.ts
│   ├── protocols.ts
│   ├── statistics.ts
│   ├── qualityChecks.ts
│   ├── reviews.ts
│   ├── exports.ts
│   ├── files.ts
│   ├── templates.ts
│   └── audit.ts
│
├── components
│   ├── common
│   │   ├── PageHeader.vue
│   │   ├── StatusTag.vue
│   │   ├── EmptyState.vue
│   │   ├── LoadingState.vue
│   │   ├── ErrorState.vue
│   │   ├── ConfirmActionDialog.vue
│   │   └── ChineseCodeText.vue
│   │
│   ├── project
│   │   ├── ProjectHeader.vue
│   │   ├── ProjectProgress.vue
│   │   ├── NextActionCard.vue
│   │   ├── ProjectSummaryCard.vue
│   │   └── ProjectTimeline.vue
│   │
│   ├── agent
│   │   ├── AgentTaskProgress.vue
│   │   ├── AgentEventTimeline.vue
│   │   ├── ClarificationQuestionCard.vue
│   │   ├── DirectionOptionCard.vue
│   │   └── AgentResultSource.vue
│   │
│   ├── literature
│   │   ├── LiteratureCard.vue
│   │   ├── LiteratureDetailDrawer.vue
│   │   ├── TrialCard.vue
│   │   ├── CitationStatus.vue
│   │   └── EvidenceScopeTag.vue
│   │
│   ├── protocol
│   │   ├── ProtocolSectionEditor.vue
│   │   ├── ProtocolSectionStatus.vue
│   │   ├── VersionCompareDrawer.vue
│   │   └── EvidenceReferencePanel.vue
│   │
│   └── review
│       ├── ReviewStatusCard.vue
│       ├── ReviewCommentEditor.vue
│       └── ReviewHistory.vue
│
├── composables
│   ├── useProjectAccess.ts
│   ├── useProjectProgress.ts
│   ├── useAgentTaskStream.ts
│   ├── useStatusLabel.ts
│   ├── useAsyncAction.ts
│   └── useUnsavedChanges.ts
│
├── constants
│   ├── routes.ts
│   ├── roles.ts
│   ├── statuses.ts
│   ├── studyTypes.ts
│   └── researchStages.ts
│
├── layouts
│   ├── MainLayout.vue
│   ├── ProjectWorkspaceLayout.vue
│   └── AdminLayout.vue
│
├── router
│   ├── index.ts
│   ├── mainRoutes.ts
│   ├── projectRoutes.ts
│   └── adminRoutes.ts
│
├── stores
│   ├── auth.ts
│   ├── currentProject.ts
│   ├── projectPermissions.ts
│   ├── notifications.ts
│   └── appDictionary.ts
│
├── types
│   ├── auth.ts
│   ├── project.ts
│   ├── agent.ts
│   ├── literature.ts
│   ├── protocol.ts
│   ├── review.ts
│   └── common.ts
│
├── views
│   ├── dashboard
│   │   └── DashboardView.vue
│   │
│   ├── projects
│   │   ├── ProjectListView.vue
│   │   ├── ProjectCreateWizard.vue
│   │   └── ProjectWorkspaceView.vue
│   │
│   ├── project
│   │   ├── ProjectOverviewView.vue
│   │   ├── ResearchIdeaView.vue
│   │   ├── LiteratureEvidenceView.vue
│   │   ├── StudyDesignView.vue
│   │   ├── ResearchProtocolView.vue
│   │   ├── StatisticalAnalysisView.vue
│   │   ├── QualityCheckView.vue
│   │   ├── ExpertReviewView.vue
│   │   ├── ProjectExportView.vue
│   │   └── ProjectSettingsView.vue
│   │
│   ├── todos
│   │   └── TodoCenterView.vue
│   │
│   ├── resources
│   │   ├── LiteratureSearchView.vue
│   │   ├── ClinicalTrialsView.vue
│   │   ├── ResearchGuidelinesView.vue
│   │   └── HistoricalProjectsView.vue
│   │
│   └── admin
│       ├── UserManagementView.vue
│       ├── HospitalManagementView.vue
│       ├── TemplateManagementView.vue
│       ├── CitationStyleView.vue
│       ├── ModelPromptManagementView.vue
│       ├── AuditLogView.vue
│       └── SystemStatusView.vue
│
└── utils
    ├── codeLabels.ts
    ├── dateTime.ts
    ├── errorMessages.ts
    └── downloadFile.ts
```

---

# 7.2 路由设计

```text
/dashboard

/projects
/projects/new

/projects/:projectKey/overview
/projects/:projectKey/idea
/projects/:projectKey/literature
/projects/:projectKey/design
/projects/:projectKey/protocol
/projects/:projectKey/statistics
/projects/:projectKey/quality
/projects/:projectKey/review
/projects/:projectKey/export
/projects/:projectKey/settings

/todos

/resources/literature
/resources/trials
/resources/guidelines
/resources/history

/admin/users
/admin/hospitals
/admin/templates
/admin/citations
/admin/models
/admin/audit
/admin/system
```

前端 URL 中可使用系统生成的公开课题标识，但页面不直接展示该标识。

---

# 7.3 状态管理边界

前端状态必须先区分为：服务端状态、会话/权限状态、纯界面状态。Pinia 不应承担所有 API 数据缓存职责。

## 服务端状态

包括：

- 课题摘要、阶段和待办；
- Agent 任务；
- 文献、研究方案和审核记录；
- 模板、引用格式和导出记录。

要求：

- 使用统一的请求缓存、失效和刷新策略；
- 明确保存成功后的缓存更新方式；
- SSE 事件只触发精确失效或增量更新，避免每个事件刷新整个任务；
- 支持断线重连、重复事件、乱序事件和页面重新进入后的状态恢复；
- 服务端仍是最终事实来源，前端不得仅依赖本地事件推演最终状态。

## Pinia 全局状态

只保存：

- 当前登录用户；
- 权限；
- 当前课题基础信息；
- 当前课题成员角色；
- 全局字典；
- 通知数量；
- 页面间必要的 Agent 任务摘要。

## 页面局部状态

保存在页面或 composable 中：

- 表单输入；
- 当前页签；
- 抽屉开关；
- 临时筛选条件；
- 当前编辑章节；
- 未保存内容；
- 当前文献详情。

禁止将所有业务对象放进一个全局 Store。

---

# 7.4 API 拆分

当前 `platform.ts` 应按业务域拆开。

示例：

```typescript
// api/projects.ts
export function getProjects(params: ProjectQuery) {}
export function createProject(data: CreateProjectRequest) {}
export function getProject(projectKey: string) {}
export function updateProject(projectKey: string, data: UpdateProjectRequest) {}

// api/agentTasks.ts
export function createResearchTask(projectKey: string, data: CreateTaskRequest) {}
export function getTask(taskKey: string) {}
export function retryTask(taskKey: string) {}
export function confirmTaskStep(taskKey: string, data: ConfirmStepRequest) {}

// api/literature.ts
export function getSearchStrategy(projectKey: string) {}
export function confirmSearchStrategy(projectKey: string, data: ConfirmSearchRequest) {}
export function getLiterature(projectKey: string, params: LiteratureQuery) {}
```

每个 API 文件只负责一个领域。

首页和课题工作区优先使用面向页面的聚合查询接口，避免前端为了展示一个“下一步行动”串行调用多个底层接口并自行拼装业务规则。

---

# 7.5 前端组件体积约束

建议编码规则：

- 页面组件尽量不超过 400 行；
- 普通业务组件尽量不超过 250 行；
- 单个 composable 尽量不超过 200 行；
- 单个 API 文件尽量不超过 250 行；
- 超过限制必须评估职责是否混合；
- 禁止再次出现一个页面同时包含全部流程。

行数只作为预警。最终验收以职责、依赖方向、可测试性、复用边界和变更影响范围为准，不要求为了满足数字机械拆分。

# 7.6 纵向切片迁移策略

禁止一次性删除旧工作台并同时重写全部页面。建议按以下顺序迁移：

1. 课题列表、课题概览、下一步行动和后端业务读模型；
2. 研究构想、澄清问题和研究方向确认；
3. 检索策略、医学文献、临床试验和研究空白；
4. 研究设计、研究方案和统计分析；
5. 质量检查、专家审核和成果导出；
6. 系统管理和科研资源。

迁移期要求：

- 旧 `StageOnePanel.vue` 作为兼容入口或放在功能开关后；
- 每完成一个切片即补齐单元、契约和端到端测试；
- 新旧入口不得同时修改同一份数据而没有版本冲突控制；
- 路由级懒加载；
- 设置主包和页面 Chunk 体积预算；
- 每个切片可独立上线和回退。

---

# 8. 后端正确性改造

# 8.1 修复研究方向确认漂移

当前风险：

1. 研究方向生成步骤使用模型生成方向；
2. 医生确认其中一个方向；
3. 后续生成研究问题时再次调用模型重新生成方向；
4. 新生成的第二个方向可能与医生确认的旧方向不一致。

整改方案：

- 研究方向生成后保存完整方向快照；
- 医生确认时保存完整已确认对象；
- 后续步骤直接使用已确认快照；
- 不允许通过方向序号重新生成；
- 方向 ID 只用于内部关联；
- 页面只显示方向名称。

建议对象：

```java
public record ConfirmedResearchDirection(
        String schemaVersion,
        String sourceDirectionId,
        UUID sourceStepRunId,
        String title,
        String researchPurpose,
        String studyType,
        String population,
        String exposureOrIntervention,
        String comparator,
        String outcome,
        List<String> dataRequirements,
        List<String> limitations,
        String contentSha256,
        Instant confirmedAt,
        UUID confirmedBy,
        UUID supersedesConfirmationId
) {
}
```

验收标准：

- 相同任务后续步骤使用的研究方向内容与医生确认内容完全一致；
- 模型重试不改变已确认方向；
- 医生如修改候选方向，保存修改后的完整对象和替代关系；
- 后续步骤接口不再接受“研究想法 + 方向序号”作为唯一输入；
- 审计可查看确认前后版本。

---

# 8.2 修复 Prompt 实际调用与审计不一致

当前风险：

- 实际模型调用可能使用研究想法解析 Prompt；
- 审计记录却标记为研究方向 Prompt；
- 无法准确追溯模型实际输入。

整改方案：

- 每一个 Agent 步骤只允许调用对应的模型方法；
- 每个模型方法绑定明确 Prompt 编码；
- 模型层返回真实调用记录；
- 步骤表保存真实 `model_call_log_id`；
- 禁止业务层伪造随机模型调用 ID。

接口建议：

```java
public interface ResearchModelService {

    ResearchIdeaProfile parseResearchIdea(ParseIdeaRequest request);

    List<ResearchDirection> generateResearchDirections(
            GenerateDirectionsRequest request);

    ResearchQuestionStructure generateResearchQuestion(
            GenerateQuestionRequest request);

    ProtocolSectionDraft generateProtocolSection(
            GenerateProtocolSectionRequest request);
}
```

---

# 8.3 Agent Worker 拆分

当前 Worker 体积过大，应改为步骤处理器模式。

```java
public interface AgentStepHandler {

    String stepCode();

    AgentStepResult execute(AgentStepContext context);
}
```

实现：

```text
ParseIdeaStepHandler
ClarificationStepHandler
GenerateDirectionsStepHandler
ConfirmDirectionStepHandler
GenerateResearchQuestionStepHandler
GenerateSearchStrategyStepHandler
PubMedSearchStepHandler
ClinicalTrialSearchStepHandler
SimilarResearchStepHandler
StudyDesignStepHandler
ProtocolDraftStepHandler
StatisticsDraftStepHandler
CitationValidationStepHandler
QualityCheckStepHandler
ExpertReviewStepHandler
DocumentExportStepHandler
```

Worker 只负责：

```text
领取任务
→ 获取当前步骤处理器
→ 执行
→ 保存结果
→ 统一异常处理
→ 更新任务状态
```

---

# 8.4 Repository 拆分

建议将大 JDBC Repository 拆分为：

```text
AgentTaskRepository
AgentStepRunRepository
AgentEventRepository
ClarificationRepository
DirectionConfirmationRepository
SearchStrategyRepository
ModelCallLogRepository
```

SQL 和 RowMapper 可进一步独立。

---

# 8.5 模型调用审计

新增模型调用日志表：

```text
ai_model_call_log
├── id
├── hospital_id
├── project_id
├── task_id
├── step_code
├── provider
├── model_name
├── logical_model_type
├── prompt_code
├── prompt_version
├── prompt_sha256
├── rendered_prompt_sha256
├── input_snapshot_ref
├── input_sha256
├── safety_assessment_json
├── model_parameters_json
├── route_reason
├── output_sha256
├── output_snapshot_ref
├── response_schema_version
├── provider_request_id
├── finish_reason
├── input_token_count
├── output_token_count
├── duration_ms
├── retry_count
├── status
├── error_code
├── created_at
└── completed_at
```

注意：

- 普通日志不记录完整敏感 Prompt；
- 只有哈希不能完成问题复现；必须能够通过 Prompt 版本、脱敏输入快照和受控输出快照重建实际调用上下文；
- 完整输入输出如需保存，应加密并严格授权，可使用受控对象存储引用而不是普通日志字段；
- 调用发起前先保存 `STARTED`，成功或失败后更新最终状态；
- 模型调用日志不得因外围业务事务回滚而消失，可使用独立事务或可靠 Outbox；
- 记录实际模型参数、路由理由、安全扫描结果、供应商请求号和结束原因；
- 真实 `model_call_log_id` 由模型调用层返回，业务层不得生成随机 UUID 冒充；
- 用户界面不展示模型调用编号；
- 审计管理员可以查看调用摘要。

# 8.6 Agent 租约、并发与步骤幂等

当前默认 Agent 租约为 30 秒，模型读取超时为 60 秒，PubMed、ClinicalTrials.gov 和 Crossref 还可能进行多次重试。长步骤可能在仍执行时被其他实例再次领取。

整改要求：

- 步骤尝试号独立于任务乐观锁 `version`；
- 领取任务时生成不可复用的 `execution_token`；
- Worker 在长步骤执行期间续租；
- 步骤完成、失败和推进下一步时必须同时校验 `execution_token`；
- 过期 Worker 的完成写入必须被数据库拒绝；
- 工具调用使用 `(task_id, step_code, attempt_no, tool_call_key)` 幂等约束；
- 已完成步骤结果不可原地覆盖，重试产生新的尝试记录；
- 任务状态、步骤结果和领域事件需定义原子提交或 Outbox 边界；
- 取消请求应在工具调用前后和持久化前检查。

验收场景：

```text
两个 Worker 同时领取同一任务
租约在模型调用期间过期
旧 Worker 晚于新 Worker 返回
外部接口超时后自动重试
应用在保存结果后、发布事件前崩溃
用户连续点击确认或重试
```

以上场景不得产生重复文献关联、重复方案版本、状态倒退或旧结果覆盖。

# 8.7 课题业务状态和页面读模型

内部 Agent 步骤不能直接充当产品业务状态。新增课题工作区聚合查询服务，统一计算：

- 课题业务状态；
- 当前业务阶段；
- 阶段完成度；
- 当前用户下一步；
- 当前用户待办；
- 允许执行的操作；
- 不允许操作的原因；
- 当前有效任务和审核版本；
- 最近动态。

建议接口：

```java
public interface ProjectWorkspaceQueryService {

    ProjectWorkspaceSummary getSummary(UUID projectId);

    List<ProjectStageView> getStages(UUID projectId);

    List<TodoItemView> getTodos(ProjectTodoQuery query);
}
```

业务读模型可以由现有任务、审核和导出状态计算产生，第一阶段不要求立刻在课题表重复保存所有派生字段。但状态计算必须集中在后端，并有契约测试。

# 8.8 审核职责与正式导出门槛

当前系统只有通用 `EXPERT` 单次审核决定和课题负责人确认。目标页面中的医学审核、统计审核和负责人确认不能只通过前端分栏实现。

实施前必须确定：

- 医学和统计是否都必须独立通过；
- 一个专家是否允许承担多个审核职责；
- 伦理和科研管理是系统内审核、外部完成项，还是匿名试点暂不纳入；
- 退回修改后哪些审核结论自动失效；
- 修改单个章节时审核失效范围；
- “科研草案”和“正式版本”的导出门槛及文档标识。

建议将审核建模为多个可指派的审核任务：

```text
MEDICAL_REVIEW
STATISTICAL_REVIEW
OWNER_CONFIRMATION
ETHICS_CONFIRMATION（根据试点决策启用）
RESEARCH_ADMIN_CONFIRMATION（根据试点决策启用）
```

导出服务必须依据审核策略计算结果，而不是只判断一个通用专家状态。

---

# 9. 数据库安全与隔离整改

# 9.1 多医院联合约束

当前仅分别关联医院和课题，不能完全保证医院与课题一致。

建议父表增加联合唯一键：

```sql
ALTER TABLE research_project
ADD CONSTRAINT uk_research_project_hospital_id_id
UNIQUE (hospital_id, id);
```

子表改为联合外键：

```sql
FOREIGN KEY (hospital_id, project_id)
REFERENCES research_project(hospital_id, id)
```

需要检查：

```text
Agent 任务 → 课题
Agent 步骤 → Agent 任务
Agent 事件 → Agent 任务
文件 → 课题
文献检索任务 → 课题
课题文献关联 → 课题和文献
临床试验关联 → 课题和试验
研究方案 → 课题
方案章节 → 研究方案
方案章节版本 → 方案章节
统计方案 → 课题
专家审核 → 课题
审核批注和审核动作 → 审核任务
导出记录 → 课题
课题成员 → 用户和课题
创建人、确认人、审核人 → 同医院用户
```

数据库约束应沿父子链逐级建立复合唯一键和复合外键，不能只约束直接包含 `project_id` 的表。

对于 `platform_user.hospital_id` 可为空的平台管理员，需要单独设计约束，医院业务表中的创建人、审核人和确认人必须通过 `(hospital_id, user_id)` 保证同院。

迁移实施前必须：

1. 扫描已有跨医院错误关联；
2. 扫描大小写重复和空格差异；
3. 输出清理报告和处理方案；
4. 先修复数据，再创建唯一索引和外键；
5. 在真实 PostgreSQL 上验证迁移；
6. 准备前向修复脚本和备份恢复点。

---

# 9.2 用户名大小写唯一性

查询使用大小写不敏感时，数据库也必须大小写不敏感唯一。

```sql
CREATE UNIQUE INDEX uk_hospital_code_lower
ON hospital(lower(code));

CREATE UNIQUE INDEX uk_user_hospital_username_lower
ON platform_user(hospital_id, lower(username))
WHERE hospital_id IS NOT NULL;

CREATE UNIQUE INDEX uk_platform_username_lower
ON platform_user(lower(username))
WHERE hospital_id IS NULL;
```

同时在保存前统一处理：

```text
医院编码：去除首尾空格并转大写
用户名：去除首尾空格并统一规范
邮箱：转小写
```

医院编码建议限制为明确的 ASCII 字符集。用户名需明确是否允许中文、全角字符和 Unicode 等价字符，并统一执行 Unicode 规范化。也可评估 PostgreSQL `citext`，但同一字段只能选择一种清晰的一致性方案。

---

# 9.3 数据库权限

正式环境至少使用三个数据库账号：

```text
迁移账号
应用读写账号
只读审计账号
```

要求：

- 应用账号无建表权限；
- 迁移账号不供运行时使用；
- 审计账号只读；
- 生产配置禁止默认密码；
- 数据库连接强制加密；
- 数据库备份加密。

---

# 10. 漏洞与安全整改

# 10.1 高优先级漏洞

## Prompt Injection

风险来源：

- 上传文档；
- 外部论文摘要；
- PubMed 内容；
- 临床试验描述；
- 用户输入。

整改：

- 将外部内容标记为不可信资料；
- System Prompt 明确禁止资料覆盖系统指令；
- 文档内容不能控制工具；
- 工具白名单；
- 参数白名单；
- 敏感工具必须人工确认；
- 增加 Prompt Injection 测试集；
- 对“忽略之前指令”等明显模式进行启发式识别和风险标记。

注意：

- 正则和关键词只能作为辅助检测，不能作为“已阻断 Prompt Injection”的验收依据；
- 即使攻击文本未被识别，外部内容也不得获得工具选择、参数决定、权限提升和系统 Prompt 读取能力；
- 模型输出必须通过结构化 Schema、业务规则和允许值校验后才能进入下一步骤；
- 对文献摘要、上传资料和用户输入分别标记来源与信任级别。

## 越权访问

整改：

- 所有课题接口后端校验医院和课题成员关系；
- 禁止仅依靠前端隐藏菜单；
- 访问文件时重新校验课题权限；
- 导出接口重新校验最终确认状态；
- 管理员接口与业务接口权限分离；
- 数据库增加联合外键。

## 模型数据外发

整改：

- 调用模型前执行敏感信息扫描；
- 拒绝患者姓名、身份证号、手机号、住院号；
- 采用允许外发字段清单，默认不发送无关原文；
- 保存脱敏和阻断规则版本及扫描结论，不保存不必要的敏感原文；
- 根据模型供应商建立出网白名单；
- 保存外发字段摘要；
- 明确模型供应商的数据保留、训练使用、地域和删除策略；
- 建立人工复核、误报处理和安全事件响应流程；
- 后续真实研究数据场景必须院内部署或去标识化；
- 模型配置页面显示数据出网风险。

## 文件上传

继续保持并完善：

- 扩展名校验；
- MIME 校验；
- 魔数校验；
- ClamAV；
- ZIP 炸弹限制；
- DOCX 内嵌对象检查；
- PDF JavaScript 和附件检查；
- 文件大小限制；
- 图片像素限制；
- 对象存储私有桶；
- 下载时权限校验；
- 文件内容安全审计。

---

# 10.2 中优先级漏洞

## 登录与会话

- Cookie 设置 `HttpOnly`；
- 生产环境启用 `Secure`；
- `SameSite=Lax` 或更严格；
- 登录成功后更换会话标识；
- 修改密码后撤销旧会话；
- 账号禁用后立即撤销会话；
- 限制并发登录；
- 增加登录设备和最近登录记录。

## CSRF

- 所有写请求验证 CSRF Token；
- 前端统一封装请求；
- 登录、退出、上传和导出接口重点验证；
- 不使用 GET 修改数据。

## XSS

- Markdown 渲染必须白名单过滤；
- 禁止直接使用未过滤 `v-html`；
- 文献摘要、模型输出和用户内容均视为不可信；
- 下载文件名转义；
- 错误消息不拼接原始 HTML。

## SSRF

外部请求仅允许访问配置中的官方域名：

```text
eutils.ncbi.nlm.nih.gov
clinicaltrials.gov
api.crossref.org
```

禁止模型或用户传入任意 URL 让后端访问。

## 日志泄露

普通日志禁止记录：

- API Key；
- Cookie；
- Session Token；
- 完整 Prompt；
- 完整模型结果；
- 患者身份信息；
- 数据库密码；
- MinIO 密钥。

凭据要求：

- 开发凭据文件放在仓库目录之外或使用系统凭据存储；
- 生产凭据由 Secret Manager、容器 Secret 或等效机制提供；
- CI 增加 Secret Scan；
- 凭据支持轮换和吊销；
- 禁止把被 `.gitignore` 忽略视为充分的凭据保护。

---

# 10.3 依赖漏洞

建立依赖治理：

```text
Maven Dependency Check
npm audit
容器镜像扫描
SBOM
Dependabot 或 Renovate
```

当前未实际使用的依赖建议移除或正式启用：

- `spring-ai-model`
- `mybatis-plus-core`
- `resilience4j-spring-boot3`

原则：

> 没有使用的依赖不应保留在正式项目中。

---

# 11. 模型与 Spring AI 后续建议

# 11.1 保留业务模型路由

继续保留自己的逻辑模型层：

```text
RESEARCH_FAST
RESEARCH_STANDARD
RESEARCH_REASONING
RESEARCH_REVIEW
RESEARCH_EMBEDDING
RESEARCH_OCR
```

页面不展示这些编码。

业务层只依赖 `ModelRouter`，底层可以：

- 继续自定义 `RestClient`；
- 或逐步接入 Spring AI；
- 或同时支持两种适配器。

## 推荐接口

```java
public interface ModelRouter {

    ResearchModel getModel(LogicalModelType type);
}
```

## 实际配置

```yaml
medical:
  ai:
    models:
      fast:
        provider: qwen
        model: ${FAST_MODEL}
      standard:
        provider: qwen
        model: ${STANDARD_MODEL}
      reasoning:
        provider: deepseek
        model: ${REASONING_MODEL}
      review:
        provider: second-provider
        model: ${REVIEW_MODEL}
```

复核模型建议使用不同模型或不同供应商。

---

# 11.2 研究方案受控生成

当前方案主要为确定性模板，建议逐步升级为：

```text
程序生成章节结构和事实输入
→ 模型只生成单个章节草稿
→ 引用只能使用分配的文献编号
→ Java 校验结构
→ 引用真实性检查
→ 人工确认
```

禁止一次性让模型生成整份方案。

---

# 11.3 RAG 建议

目前 pgvector 主要为预留。

优先用于：

- 院内历史课题；
- 科研管理制度；
- 伦理申请模板；
- 申报书模板；
- 研究方法学资料；
- 已授权的指南和全文；
- 文档模板说明。

不建议未经授权直接向量化商业论文全文。

# 11.4 当前研究类型开放范围

当前从研究设计推荐、方案生成、统计草案到质量检查的完整闭环只覆盖：

```text
横断面研究
队列研究
病例对照研究
```

质量检查当前主要对应观察性研究报告规范（STROBE）。

因此第一轮产品改造中：

- 新建课题和方向确认只开放以上三类；
- 系统综述、诊断准确性研究、随机对照试验可以展示为后续规划，但不能进入正式生成流程；
- 每新增一种研究类型，必须同时具备研究问题结构、设计规则、方案模板、统计规则、对应报告规范和专家验收集；
- 不得仅增加一个枚举或中文标签就宣称支持新的研究类型。

---

# 12. 文档和图片解析改造

# 12.1 当前问题

当前解析主要返回纯文本：

```text
PDF → PDFBox
DOCX → Apache POI
TXT/MD → UTF-8
```

不足：

- 扫描 PDF 无 OCR；
- 图片不支持；
- 表格结构丢失；
- 页码和章节信息丢失；
- 图表无法理解；
- 不能准确定位信息来源。

## 目标结构

```java
public record ParsedDocument(
        String documentKey,
        String title,
        List<ParsedPage> pages,
        List<ParsedSection> sections,
        List<ParsedTable> tables,
        List<ParsedImage> images,
        Map<String, Object> metadata,
        ParseQuality quality
) {
}
```

## 推荐流程

```text
Java 文件接收与安全检查
→ 文件类型识别
→ 普通 PDF/DOCX 工具解析
→ 扫描件转 OCR
→ 版面与表格恢复
→ 保存结构化结果
→ 模型进行语义提取
→ 人工复核
```

## 推荐技术

Java：

- Apache Tika；
- Apache PDFBox；
- Apache POI。

Python 文档服务：

- FastAPI；
- PaddleOCR；
- MinerU 或 Docling；
- 表格和版面识别。

原则：

> 能用工具确定性提取的内容，不让模型猜。

---

# 13. 后续开发建议

# 13.1 阶段零：决策冻结与基线建立

在修改代码前完成：

1. 确认匿名试点输出是“科研草案”还是“正式版本”；
2. 确认医学、统计、伦理、科研管理和负责人审核矩阵；
3. 确认第一版只开放三类观察性研究；
4. 冻结任务状态、课题业务阶段和审核状态定义；
5. 明确模型审计内容、保存期限和访问权限；
6. 扫描现有跨医院关联、大小写重复和迁移风险；
7. 固化当前后端、前端、数据库迁移和端到端测试基线；
8. 为旧工作台建立功能开关和回退入口。

产出：

- 状态机和审核矩阵 ADR；
- 页面读模型和 API 契约；
- 数据迁移检查报告；
- 当前测试基线；
- 分切片实施清单。

---

# 13.2 第一阶段：P0 正确性、并发与安全整改

优先完成：

1. 修复研究方向确认漂移；
2. 修复 Prompt 实际调用与审计不一致；
3. 建立真实模型调用日志；
4. 增加独立步骤尝试号、租约续期、执行令牌和旧 Worker 写入隔离；
5. 为外部工具调用和步骤结果增加幂等约束；
6. 明确任务、步骤和事件的一致性提交边界；
7. 增加医院、课题、任务、用户和子资源复合外键；
8. 修复用户名和医院编码大小写及规范化唯一性；
9. 明确医学、统计和负责人审核门槛；
10. 完成越权、Prompt Injection、SSRF、XSS、CSRF 回归测试；
11. 清理或正式启用未使用依赖；
12. 让真实 PostgreSQL 迁移和隔离测试在 CI 中强制执行。

第一阶段可拆成两个独立交付批次：

```text
P0-A：Agent 正确性、租约、幂等和模型审计
P0-B：租户复合约束、身份规范化、审核门槛和安全回归
```

截至 2026-07-30，P0-A、P0-B 已按推荐默认值完成并通过阶段门禁，实施证据见
[P0-A 阶段报告](P0-A-阶段报告.md)和 [P0-B 阶段报告](P0-B-阶段报告.md)。V20～V26 尚未
应用到仍运行旧代码的本地 V19 数据库；实际发布必须停旧 Worker，确认没有 `RUNNING` 任务并
完成备份与只读扫描后再迁移，禁止新旧版本混合运行。

第二阶段也已在 2026-07-30 按推荐默认值完成并通过门禁：V27 建立项目读模型游标、项目事件和
动作幂等账本，后端形成摘要/阶段/待办/统一动作/项目 SSE，前端完成首页、课题、待办、概览和
“构想—澄清—方向确认”首批纵向切片；V2 默认关闭且旧版固定可回退。最终后端 130 项测试、
前端 11 项单测及 4 项本地 E2E 零失败，Review 后无遗留 P0/P1。实施证据见
[第二阶段阶段报告](阶段2-阶段报告.md)。

第三阶段也已在 2026-07-30 完成并通过门禁：证据、设计、方案、统计、质量、三方内部审核和
科研草案导出均已迁入工作区 V2；方案支持人工修订、历史、比较、并发保护和安全重提交；DOCX
由服务端强制写入科研草案边界。最终后端 131 项测试、前端 13 项单测及 5 项本地 E2E 零失败，
阶段必需的真实 PostgreSQL 测试全部实际执行，Review 后无遗留 P0/P1。实施证据见
[第三阶段阶段报告](阶段3-阶段报告.md)。当前已自动进入第四阶段核验。

---

# 13.3 第二阶段：业务读模型与首批用户流程

目标：

- 建立课题业务状态、阶段、下一步、待办和允许操作读模型；
- 建立首页、我的课题、待办中心和课题概览；
- 建立课题工作区布局和阶段导航；
- 迁移研究构想、澄清问题和研究方向确认；
- 建立统一中文字典；
- 移除医生页面的内部 ID 和英文状态；
- 按已迁移领域拆分 `StageOnePanel.vue` 和 `platform.ts`；
- 建立路由级权限控制；
- 建立未保存内容恢复和离开提示；
- 保留旧工作台兼容入口。

---

# 13.4 第三阶段：其余纵向切片与结构提取

- 迁移文献证据、研究设计、研究方案、统计分析、质量检查、审核和导出页面；
- 每迁移一个切片，同时提取对应 StepHandler、Repository、API 和类型；
- 模型方法按步骤拆分并统一经过 ModelRouter；
- Prompt 统一版本化；
- 补齐章节手工编辑、章节重生成、版本历史和版本比较；
- 完成医学和统计独立审核；
- 增加项目包依赖检查；
- 删除旧工作台兼容壳；
- 建立前端 Bundle、可访问性和关键流程端到端测试门禁。

---

# 13.5 第四阶段：AI 能力增强

- 真实多模型路由；
- 复核模型；
- 章节级研究方案生成；
- 文献内容与结论一致性检查；
- 研究类型推荐规则与模型结合；
- 建立匿名科研评测集；
- 统计专家和医学专家评分；
- 建立单课题 Token 和成本统计。

---

# 13.6 第五阶段：知识库与文档解析

- 扫描 PDF OCR；
- 图片解析；
- 表格结构恢复；
- 页码和章节定位；
- 院内历史课题知识库；
- 科研制度知识库；
- pgvector 实际接入；
- 混合检索；
- 来源引用展示。

# 13.7 第六阶段：匿名数据试点

- 使用合成或经过批准的匿名数据；
- 选择有限科室和有限课题类型；
- 记录任务成功率、人工修改量、审核退回率和平均完成时间；
- 记录模型成本、外部服务失败率和任务排队时间；
- 开展医生、医学专家和统计专家可用性测试；
- 完成备份恢复、凭据轮换、供应商故障和任务重复执行演练；
- 根据试点结果决定是否扩大研究类型和用户范围。

---

# 14. 改造实施顺序

| 阶段 | 内容 | 建议周期 |
|---|---|---:|
| 阶段零 | 决策冻结、API 契约和基线 | 1 周 |
| 第一阶段 | P0 正确性、并发、审计和租户整改 | 3～5 周 |
| 第二阶段 | 业务读模型和首批前端纵向切片 | 3～5 周 |
| 第三阶段 | 其余纵向切片和结构提取 | 5～8 周 |
| 第四阶段 | 多模型能力与质量评测增强 | 3～4 周 |
| 第五阶段 | OCR、知识库和 RAG | 4～6 周 |
| 第六阶段 | 匿名数据试点和优化 | 3～4 周 |

以上周期仅适用于具备后端、前端、测试和医学/统计评审能力的小团队参考，不包含采购、合规审批和外部供应商等待时间。

建议不要同时大规模修改前端、数据库、Agent 流程和模型逻辑。后端结构提取不单独进行大爆炸式重构，而是随纵向切片逐步完成。每个阶段必须有独立退出标准。

## 14.1 当前验证基线

2026-07-30 本地验证结果：

- 后端执行 94 个测试，0 失败，12 个跳过；
- 跳过项包括真实 PostgreSQL/Flyway、MinIO 和真实外部 API 测试；
- 前端仅有 1 个自动化测试并通过；
- 前端类型检查、生产构建和 ESLint 通过；
- 生产构建主 JavaScript Chunk 约 1.06 MB，存在体积警告。

第一阶段完成前，真实 PostgreSQL/Flyway 测试不得继续在 CI 中因缺少 Docker 而静默跳过。前端拆分前必须先覆盖当前主流程的最小端到端回归。

第二阶段完成后的最新增量基线为：后端 130 项测试零失败（阶段必需的本地真实 PostgreSQL
迁移、隔离、幂等和并发测试全部实际执行），前端 11 项单测、2 项旧版 E2E 和 2 项 V2 E2E
通过。V2 独立懒加载块为 24.87 kB；公共主块仍约 1.067 MB，作为第三阶段 P2 拆包项继续追踪。

第三阶段完成后的最新增量基线为：后端 131 项测试零失败，空库/V19/V26 升级、两医院隔离和
方案章节真实 PostgreSQL 并发均实际执行；前端 13 项单测、2 项旧版 E2E 和 3 项 V2 E2E
通过。V2 路由块为 28.74 kB，七个 artifact 块为 1.25～6.73 kB；公共主块约 1.074 MB 的
既有 P2 警告继续追踪。

---

# 15. 前端重构验收标准

## 菜单与页面

- 登录后可看到首页、我的课题、待办中心、科研资源；
- 课题工作区有独立菜单；
- 一个页面不再承载全流程；
- 管理功能与课题功能分离；
- 用户可以从首页直接进入下一步待办。
- 首页、概览和待办所需业务语义由后端读模型提供，前端不解释 `STEP_*`。

## 引导性

- 新建课题使用分步向导；
- 每个阶段都有“当前状态、需要处理、下一步”；
- 阶段不可操作时给出原因；
- 失败时提供重试或联系管理员建议；
- 医生不需要了解内部 Agent 步骤编码。
- 页面刷新、SSE 断线重连和重复事件不会导致状态倒退或按钮错误。

## 中文化

- 普通页面无 UUID、数据库 ID；
- 无 `DIR-02`、`STEP_08`；
- 无未翻译状态码；
- 专业缩写首次出现附中文说明；
- 错误提示使用中文业务语言；
- 下载文件名使用课题名称和版本。

## 工程质量

- `StageOnePanel.vue` 被删除或仅保留兼容壳；
- 页面和组件职责清晰，超出建议行数时有合理说明；
- API 按业务域拆分；
- 状态字典统一管理；
- 路由按业务域拆分；
- 路由级懒加载和 Bundle 体积预算生效；
- 关键流程有单元、契约和端到端自动化测试；
- 关键医生操作支持键盘使用并通过基础可访问性检查；
- 每个纵向切片可独立上线和回退。

---

# 16. 安全整改验收标准

- 医院间无法越权访问课题、文件、任务和导出；
- 数据库联合约束阻止错误跨医院关联；
- 用户名大小写重复无法创建；
- 外部 URL 不在白名单时无法访问；
- Prompt Injection 测试即使未命中关键词，也不能改变工具、权限和系统指令；
- 模型外发前敏感信息检查生效；
- 文件下载必须重新校验权限；
- Markdown 和模型输出经过 XSS 过滤；
- 普通日志无 API Key、Token 和敏感内容；
- 模型调用可追溯到真实模型、Prompt 和任务；
- 租约过期和多 Worker 并发不会导致重复执行或旧结果覆盖；
- 所有真实 PostgreSQL 迁移、复合外键和跨医院隔离测试在 CI 中执行；
- 审核前无法导出正式版本；
- 科研草案和正式版本具有不同的审核门槛和明显文档标识；
- 依赖扫描无未处理高危漏洞。

---

# 17. Codex 执行建议

本文件当前位于：

```text
doc/完善改造/医疗研究Agent项目完善改造方案.md
```

后续 Codex 任务应始终引用该实际路径，避免同时维护 `doc` 和 `docs` 两份方案。

同时在 `AGENTS.md` 增加：

```md
## 当前改造优先级

1. 先修复方向快照、Prompt、模型审计、租约、幂等和旧 Worker 回写问题。
2. 再补齐租户复合约束、身份规范化和审核门槛。
3. 前端改造前先建立后端业务状态、下一步和待办读模型。
4. 禁止继续向 StageOnePanel.vue 增加功能。
5. 前端不得通过 STEP_* 推断业务阶段和按钮权限。
6. 前端页面禁止直接显示内部 ID 和状态编码。
7. 仅开放已经具备完整生成和质量检查链的研究类型。
8. 每次只完成一个可独立验收、上线和回退的切片。
9. 每次数据库迁移必须在真实 PostgreSQL 上验证。
```

给 Codex 的第一条代码改造指令建议仅执行 P0-A，不一次性混入前端和全部数据库重构：

```text
请先阅读：

1. AGENTS.md
2. doc/完善改造/医疗研究Agent项目完善改造方案.md
3. doc/开发方案/医疗研究Agent完整开发框架.md

当前只执行 P0-A：Agent 正确性、并发和模型审计。

请完成：

1. 修复研究方向确认后在后续步骤重新生成的问题；
2. 修复模型实际 Prompt 与审计记录不一致的问题；
3. 建立真实模型调用日志；
4. 将步骤尝试号与任务 version 分离；
5. 增加 execution_token、租约续期和旧 Worker 写入隔离；
6. 为步骤结果和外部工具调用增加幂等保护；
7. 补充单元测试、并发测试和真实 PostgreSQL 集成测试。

暂时不要重构前端，不要新增业务功能，不要引入微服务。

完成后输出：
- 问题原因；
- 修改文件；
- 数据库迁移；
- 测试结果；
- 风险与后续事项。
```

P0-A 验收后再执行 P0-B：租户复合约束、身份规范化、审核门槛和安全回归。P0-B 验收后，按纵向切片执行前端和对应后端读模型改造。

---

# 18. 已确认的执行决策

2026-07-30，项目负责人确认本章全部采用推荐默认值。以下内容是后续设计、编码、Review 和验收的正式约束，不再作为阻塞性问题重复询问。

## 18.1 已确认的关键决策

### 1. 匿名试点导出的文件性质

需要确认：

- 只允许导出带明显标识的“科研草案”；
- 还是允许经过完整审核后导出“正式版本”。

已确认决定：

> 第一轮匿名试点只导出“科研草案”，页眉、封面和文件属性均标记“仅供科研设计讨论，未经伦理和科研管理审批”。

### 2. 审核矩阵

需要确认：

- 医学和统计是否必须分别通过；
- 一个专家能否同时承担两个职责；
- 伦理和科研管理是否在系统内确认；
- 哪些修改会使已有审核失效。

已确认决定：

> 医学审核、统计审核和课题负责人确认分别完成；伦理和科研管理作为正式版本的外部完成项，匿名试点草案不宣称完成正式审批。

### 3. 第一版研究类型范围

已确认决定：

> 只开放横断面、队列和病例对照研究。系统综述、诊断准确性和随机对照试验暂时显示为未开放。

### 4. 模型审计内容和保存期限

需要确认：

- 是否保存加密后的完整输入输出；
- 保存位置、保存期限和可查看角色；
- 是否允许审计管理员查看脱敏内容；
- 供应商返回内容的删除策略。

已确认决定：

> 普通数据库保存元数据、哈希和脱敏结构化快照；必要的完整输入输出加密存放在独立私有对象存储，默认仅安全审计角色可按审批查看，并设置明确保留期限。

### 5. 现有数据库数据处理

需要确认当前 PostgreSQL 是否已经存在需要保留的真实或试点数据。复合外键和大小写唯一索引实施前，必须决定重复数据、跨医院错误关联和非法用户名如何清理。

已确认决定：

> 先只生成扫描报告，不自动删除或合并任何现有数据；由项目负责人确认清理清单后再执行迁移。

## 18.2 已确认的实施默认值

### 6. 部署实例数

即使第一轮只有单实例，也按多实例安全实现租约和执行令牌，避免后续扩容重写。

### 7. 公开课题标识

使用不可枚举的公开 `projectKey` 作为 URL 标识，数据库 UUID 继续只用于内部关联。

### 8. 前端迁移方式

采用功能开关保留旧工作台，按纵向切片逐步替换，不进行一次性重写。

### 9. 未使用依赖

若 Spring AI、MyBatis-Plus 或 Resilience4j 未在当前两个里程碑内正式使用，则先移除；如确定即将使用，应通过 ADR 说明保留原因和启用日期。

### 10. 测试环境

CI 必须提供真实 PostgreSQL 测试环境。外部 API Live Test 可以继续显式触发，但数据库迁移和租户隔离测试不得静默跳过。

---

# 19. 最终结论

当前项目已经具备良好的医疗科研 Agent 主链和安全基础，但当前产品形态仍是“开发工作台”，还不是医生容易使用的科研助手。

下一阶段最关键的不是增加更多 Agent 能力，而是：

1. 修复流程正确性和审计问题；
2. 修复 Agent 租约、重复执行、步骤尝试号和幂等问题；
3. 明确审核矩阵、科研草案和正式版本边界；
4. 建立后端业务状态、阶段、下一步和待办读模型；
5. 将前端从单页全流程按纵向切片改为课题工作区；
6. 使用分阶段引导代替内部步骤驱动；
7. 全面隐藏内部 ID 和无意义英文编码；
8. 使用统一中文状态和专业术语说明；
9. 加强数据库租户隔离和安全验证；
10. 在结构稳定并通过匿名试点后再增强 OCR、RAG 和多模型能力。

完成本轮改造后，系统才能从“研发人员能够操作的完整原型”升级为“医生能够理解、愿意使用、可以进入匿名科研试点的产品”。
