import { expect, test } from '@playwright/test'

test('医生可复核修订并确认版本化 PubMed 检索策略', async ({ page }) => {
  let confirmed = false
  let designConfirmed = false
  let confirmedQuery = ''
  let reviewStatus = 'WAITING_EXPERT_REVIEW'
  let reviewVersion = 0
  let medicalApproved = false
  let statisticalApproved = false
  let exportCompleted = false
  const reviewComments: Array<Record<string, unknown>> = []
  const reviewHistory: Array<Record<string, unknown>> = [{
    id: 'review-action-opened',
    actionType: 'REVIEW_OPENED',
    reviewRoundNo: 1,
    actorUserId: 'user-1',
    summary: 'STEP16 完成，已提交专家审核。',
    occurredAt: '2026-07-28T01:10:00Z',
  }]
  const generatedQuery = [
    '("2型糖尿病成人"[Title/Abstract])',
    'AND ("SGLT2抑制剂"[Title/Abstract])',
    'AND ("eGFR变化"[Title/Abstract])',
    'AND (cohort studies[MeSH Terms] OR cohort study[Title/Abstract])',
  ].join('\n')

  const strategy = () => ({
    schemaVersion: 'search-strategy/v1',
    generatorVersion: 'deterministic-peco/v1',
    queryVersion: 'pubmed-query/v1',
    confirmationStatus: confirmed ? 'CONFIRMED' : 'PENDING_CONFIRMATION',
    originalResearchQuestion: 'SGLT2抑制剂是否影响12个月eGFR变化？',
    databases: ['PUBMED', 'CLINICAL_TRIALS_GOV'],
    concepts: [
      { code: 'POPULATION', label: '研究人群', terms: ['2型糖尿病成人'], required: true },
      { code: 'EXPOSURE', label: '暴露/干预', terms: ['SGLT2抑制剂'], required: true },
      { code: 'OUTCOME', label: '结局', terms: ['eGFR变化'], required: true },
      {
        code: 'STUDY_DESIGN',
        label: '研究设计',
        terms: ['cohort studies[MeSH Terms]'],
        required: true,
      },
    ],
    generatedPubmedQuery: generatedQuery,
    pubmedQuery: confirmed ? confirmedQuery : generatedQuery,
    filters: ['不自动限定语言、发表时间、年龄或全文可用性'],
    limitations: [
      '当前策略仅覆盖 PubMed，未覆盖 CNKI、万方、维普及灰色文献',
      '结构化概念由 PECO 自动转换，执行检索前必须由医生或信息专家复核',
    ],
  })

  const designRecommendation = () => ({
    schemaVersion: 'observational-design-recommendation-result/v1',
    recommendationTaskId: 'design-1',
    recommendedAt: '2026-07-28T01:04:00Z',
    recommendedStudyType: 'COHORT',
    primaryOutcomeCandidate: '12 个月 eGFR 绝对变化',
    alternatives: [
      {
        rank: 1,
        studyType: 'COHORT',
        score: 100,
        feasibilityStatus: 'READY',
        rationale: '与医生已确认的研究方向一致；当前必填信息完整。',
        requiredFields: ['population', 'exposure', 'comparator', 'outcome', 'timeFrame'],
        missingFields: [],
        biasRisks: ['残余混杂', '时间零点或不死时间偏倚', '失访偏倚'],
        evidenceConsiderations: ['当前证据未充分覆盖本院门诊人群。'],
      },
      {
        rank: 2,
        studyType: 'CASE_CONTROL',
        score: 40,
        feasibilityStatus: 'READY',
        rationale: '作为替代观察性设计进行比较；当前必填信息完整。',
        requiredFields: ['population', 'exposure', 'comparator', 'outcome'],
        missingFields: [],
        biasRisks: ['病例与对照选择偏倚', '回忆或信息偏倚', '对照来源与匹配不当'],
        evidenceConsiderations: ['当前证据未充分覆盖本院门诊人群。'],
      },
      {
        rank: 3,
        studyType: 'CROSS_SECTIONAL',
        score: 40,
        feasibilityStatus: 'READY',
        rationale: '作为替代观察性设计进行比较；当前必填信息完整。',
        requiredFields: ['population', 'exposure', 'outcome', 'setting'],
        missingFields: [],
        biasRisks: ['时序不明与因果推断限制', '选择偏倚', '患病率-幸存者偏倚'],
        evidenceConsiderations: ['当前证据未充分覆盖本院门诊人群。'],
      },
    ],
    readyForProtocolDraft: true,
    unresolvedItems: [],
    requiredConfirmations: ['确认观察性研究类型', '确认或修订主要终点', '授权进入正式研究方案生成'],
    confirmationStatus: designConfirmed ? 'CONFIRMED' : 'PENDING_CONFIRMATION',
    confirmedStudyType: designConfirmed ? 'COHORT' : undefined,
    confirmedPrimaryOutcome: designConfirmed ? '12 个月 eGFR 绝对变化' : undefined,
    protocolGenerationAuthorized: designConfirmed,
    confirmedBy: designConfirmed ? 'user-1' : undefined,
    confirmedAt: designConfirmed ? '2026-07-28T01:05:00Z' : undefined,
    inputSha256: 'e'.repeat(64),
    algorithmVersion: 'observational-design-rules/v1',
    limitations: ['该结果不替代流行病学、统计学和临床专家评审。'],
  })

  const protocolDraft = () => {
    const definitions = [
      ['TITLE', '课题名称'],
      ['ABSTRACT', '摘要'],
      ['BACKGROUND', '研究背景'],
      ['RESEARCH_STATUS', '国内外研究现状'],
      ['RESEARCH_GAP', '潜在研究空白'],
      ['OBJECTIVES', '研究目标'],
      ['HYPOTHESIS', '研究假设'],
      ['STUDY_DESIGN', '研究设计'],
      ['PARTICIPANTS', '研究对象'],
      ['ELIGIBILITY', '纳入与排除标准'],
      ['OUTCOMES_VARIABLES', '变量和终点'],
      ['DATA_COLLECTION', '数据收集'],
      ['STATISTICAL_ANALYSIS', '统计分析'],
      ['BIAS_CONTROL', '偏倚与混杂控制'],
      ['ETHICS_DATA_SECURITY', '伦理和数据安全'],
      ['SCHEDULE', '研究进度'],
      ['EXPECTED_RESULTS', '预期成果'],
      ['REFERENCES', '参考文献'],
    ] as const
    return {
      schemaVersion: 'research-protocol-draft/v1',
      protocolId: 'protocol-1',
      generatedAt: '2026-07-28T01:06:00Z',
      studyType: 'COHORT',
      title: '2 型糖尿病成年患者中 SGLT2 抑制剂与 eGFR 变化关联的队列研究',
      sections: definitions.map(([sectionCode, title], index) => ({
        sectionId: `section-${index + 1}`,
        sectionCode,
        title,
        sortOrder: index + 1,
        versionNo: sectionCode === 'STATISTICAL_ANALYSIS' ? 2 : 1,
        content: sectionCode === 'STATISTICAL_ANALYSIS'
          ? '## 样本量参数（全部待提供，不执行计算）\n'
            + '- 显著性水平：MISSING_NEEDS_INPUT\n'
            + '- 检验效能：MISSING_NEEDS_INPUT'
          : sectionCode === 'REFERENCES'
            ? '1. Empagliflozin in Patients with Chronic Kidney Disease PMID:36331190'
            : `${title}初始草案，待医生和专家复核。`,
        contentFormat: 'MARKDOWN',
        origin: 'AGENT_DETERMINISTIC',
        evidenceStatus: sectionCode === 'REFERENCES'
          ? 'VERIFIED_METADATA' : 'NEEDS_EXPERT_REVIEW',
        sourceIdentifiers: sectionCode === 'REFERENCES' ? ['PMID:36331190'] : ['PECO'],
        issuesToConfirm: ['待专家逐章确认'],
      })),
      issuesToConfirm: ['在 STEP15 完成事实主张与引用依据验证'],
      inputSha256: 'f'.repeat(64),
      generatorVersion: 'deterministic-observational-protocol/v1',
      limitations: ['本草案不替代医学、统计学、伦理或科研管理专家审核。'],
    }
  }

  const statisticalAnalysisDraft = () => {
    const statisticalSection = protocolDraft().sections[12]
    const parameters = [
      ['OUTCOME_TYPE', '主要终点变量类型'],
      ['TARGET_EFFECT', '具有临床意义的目标效应量'],
      ['ALPHA', '显著性水平'],
      ['POWER', '检验效能'],
      ['BASELINE_EVENT_OR_SD', '对照组事件率或连续终点标准差'],
      ['EXPOSURE_ALLOCATION_RATIO', '暴露组与对照组比例'],
      ['FOLLOW_UP_DURATION', '计划随访时长'],
      ['LOSS_TO_FOLLOW_UP', '预计失访或删失比例'],
    ]
    return {
      schemaVersion: 'statistical-analysis-draft/v1',
      draftId: 'statistical-draft-1',
      protocolId: 'protocol-1',
      generatedAt: '2026-07-28T01:07:00Z',
      studyType: 'COHORT',
      primaryOutcome: '12 个月 eGFR 绝对变化',
      outcomeTypeStatus: 'NEEDS_EXPERT_CONFIRMATION',
      descriptiveAnalysis: ['连续变量按分布报告均值与标准差或中位数与四分位数。'],
      primaryAnalysisCandidates: ['事件时间终点候选为生存分析模型。'],
      secondaryAnalysis: ['次要终点需与主要分析明确区分。'],
      covariates: ['最终协变量清单待统计学专家确认。'],
      potentialConfounders: ['候选混杂因素包括人口学特征和基线疾病严重程度。'],
      stratifiedAnalyses: ['分层变量和切点必须预先指定。'],
      subgroupAnalyses: ['亚组分析仅作为预先指定的探索性分析。'],
      sensitivityAnalyses: ['使用替代变量定义重复主要分析。'],
      missingDataPlan: ['逐变量报告缺失数量、比例和缺失模式。'],
      multipleComparisonPlan: ['次要终点和亚组的多重比较策略待确认。'],
      modelDiagnostics: ['检查模型收敛、残差和高影响观察。'],
      effectMeasureCandidates: ['均值差', '风险差', '风险比（RR）', '风险函数比（HR）'],
      confidenceIntervalPlan: '置信水平和显著性水平必须由统计学专家在分析前确认。',
      sampleSizeParameters: parameters.map(([code, label]) => ({
        code,
        label,
        required: true,
        valueStatus: 'MISSING_NEEDS_INPUT',
        value: null,
        unit: null,
        rationale: '必须由临床意义、既有证据或统计学专家提供。',
      })),
      recommendedSoftware: ['R、SAS 或 Stata 均可作为候选。'],
      issuesToConfirm: ['提供全部样本量参数后，才能调用确定性计算函数。'],
      statisticalSectionVersion: statisticalSection,
      inputSha256: '1'.repeat(64),
      generatorVersion: 'deterministic-observational-statistics/v1',
      limitations: ['本步骤不计算、不猜测也不承诺最终样本量。'],
    }
  }

  const claimCitationValidation = () => ({
    schemaVersion: 'claim-citation-validation-result/v1',
    validationTaskId: 'claim-validation-1',
    protocolId: 'protocol-1',
    validatedAt: '2026-07-28T01:08:00Z',
    claimCount: 2,
    citationLinkCount: 1,
    abstractOnlyClaimCount: 1,
    needsExpertReviewClaimCount: 1,
    claims: [
      {
        claimId: 'claim-1',
        sectionId: 'section-4',
        sectionCode: 'RESEARCH_STATUS',
        claimOrder: 1,
        claimType: 'EVIDENCE_SUMMARY',
        claimText: '当前检索发现一项摘要级肾脏结局研究。',
        supportStatus: 'ABSTRACT_ONLY',
        expertConfirmationStatus: 'PENDING_REVIEW',
        citationLinks: [{
          linkId: 'claim-link-1',
          claimId: 'claim-1',
          linkOrder: 1,
          sourceType: 'PUBMED',
          pmid: '36331190',
          doi: '10.1056/nejmoa2204233',
          title: 'Empagliflozin in Patients with Chronic Kidney Disease',
          supportLevel: 'ABSTRACT_ONLY',
          evidenceScope: 'ABSTRACT_ONLY',
          evidenceExcerpt: 'Empagliflozin was evaluated in people with chronic kidney disease.',
          excerptLocation: 'PUBMED_ABSTRACT',
          excerptSha256: '3'.repeat(64),
          citationValidationStatus: 'VERIFIED',
          manualConfirmationStatus: 'PENDING_REVIEW',
        }],
        issuesToConfirm: ['必须完成全文审阅并由专家确认支持关系'],
      },
      {
        claimId: 'claim-2',
        sectionId: 'section-5',
        sectionCode: 'RESEARCH_GAP',
        claimOrder: 1,
        claimType: 'POTENTIAL_RESEARCH_GAP',
        claimText: '当前证据未充分覆盖本院门诊人群。',
        supportStatus: 'NEEDS_EXPERT_REVIEW',
        expertConfirmationStatus: 'PENDING_REVIEW',
        citationLinks: [],
        issuesToConfirm: ['没有可追溯到 STEP10 核验结果的引用，当前证据不足'],
      },
    ],
    inputSha256: '4'.repeat(64),
    validatorVersion: 'deterministic-claim-citation-linker/v1',
    limitations: [
      '当前没有接入合法开放的 PMC 全文，因此不会输出 FULL_TEXT 或 SUPPORTED 结论。',
    ],
  })

  const strobeCompletenessCheck = () => {
    const statusFor = (number: number) => {
      if ([3, 4].includes(number)) return 'COVERED'
      if ([10, 22].includes(number)) return 'MISSING'
      if (number >= 13 && number <= 21) return 'NEEDS_EXPERT_REVIEW'
      return 'PARTIALLY_COVERED'
    }
    const items = Array.from({ length: 22 }, (_, index) => {
      const number = index + 1
      const itemCode = `STROBE-${String(number).padStart(2, '0')}`
      const status = statusFor(number)
      return {
        itemResultId: `strobe-item-${number}`,
        itemCode,
        sectionGroup: number <= 3 ? '引言' : number <= 12 ? '方法' : '结果与讨论',
        requirementSummary: number === 10
          ? '说明研究样本量的形成依据'
          : `STROBE 主条目 ${number} 的报告完整性要求`,
        studyType: 'COHORT',
        status,
        mappedSectionCodes: number === 10 ? ['STATISTICAL_ANALYSIS'] : [],
        evidenceSnippets: number === 10
          ? ['样本量参数：MISSING_NEEDS_INPUT'] : [],
        message: status === 'MISSING'
          ? '当前方案尚未覆盖该条目。'
          : status === 'NEEDS_EXPERT_REVIEW'
            ? '方案阶段尚无研究结果，不能自动判断该条目。'
            : '当前方案提供了对应覆盖线索。',
        suggestion: '由专家补充并复核正式报告内容。',
        requiresExpertReview: status !== 'MISSING',
      }
    })
    return {
      schemaVersion: 'strobe-completeness-check-result/v1',
      checkTaskId: 'strobe-check-1',
      protocolId: 'protocol-1',
      checkedAt: '2026-07-28T01:09:00Z',
      guidelineCode: 'STROBE',
      guidelineVersion: 'STROBE-2007-COMBINED/v1',
      studyType: 'COHORT',
      totalItemCount: 22,
      coveredCount: 2,
      partiallyCoveredCount: 9,
      missingCount: 2,
      notApplicableCount: 0,
      needsExpertReviewCount: 9,
      items,
      inputSha256: '5'.repeat(64),
      checkerVersion: 'deterministic-strobe-2007-precheck/v1',
      sourceReference: 'https://www.strobe-statement.org/checklists/',
      automaticPrecheckDisclaimer:
        '自动预检查，不能替代医学、统计学或科研管理专家审核。'
        + 'STROBE 仅用于报告完整性检查，不是研究质量评分工具。',
      limitations: ['检查结果不包含总分、百分比、等级或排名。'],
    }
  }

  const expertReview = () => ({
    reviewTaskId: 'review-task-1',
    projectId: 'project-search-1',
    agentTaskId: 'task-search-1',
    protocolId: 'protocol-1',
    strobeCheckTaskId: 'strobe-check-1',
    status: reviewStatus,
    reviewRoundNo: 1,
    submittedBy: 'user-1',
    submittedAt: '2026-07-28T01:10:00Z',
    expertReviewerId: medicalApproved ? 'medical-user' : undefined,
    expertDecision: medicalApproved ? 'APPROVE' : undefined,
    expertSummary: medicalApproved ? '医学审核通过。' : undefined,
    expertDecidedAt: medicalApproved ? '2026-07-28T01:12:00Z' : undefined,
    statisticalReviewerId: statisticalApproved ? 'statistical-user' : undefined,
    statisticalDecision: statisticalApproved ? 'APPROVE' : undefined,
    statisticalSummary: statisticalApproved ? '统计审核通过。' : undefined,
    statisticalDecidedAt: statisticalApproved ? '2026-07-28T01:12:30Z' : undefined,
    ownerConfirmedBy: reviewStatus === 'APPROVED' ? 'owner-user' : undefined,
    ownerConfirmedAt: reviewStatus === 'APPROVED' ? '2026-07-28T01:13:00Z' : undefined,
    sectionsLocked: reviewStatus === 'APPROVED',
    version: reviewVersion,
    comments: reviewComments,
    history: reviewHistory,
  })

  const documentTemplate = {
    id: 'template-version-1',
    templateCode: 'OBSERVATIONAL_PROTOCOL',
    templateName: '观察性研究方案模板',
    versionNo: 1,
    status: 'PUBLISHED',
    contentSha256: '6'.repeat(64),
    contentSize: 28432,
    placeholderSchemaVersion: 'controlled-docx-placeholders/v1',
    placeholders: [
      '${project.title}',
      '${research.background}',
      '${research.question}',
      '${research.references}',
    ],
    validationStatus: 'VALID',
    validationMessage: '模板结构和占位符校验通过',
    createdBy: 'admin-1',
    createdAt: '2026-07-28T01:00:00Z',
    publishedBy: 'admin-1',
    publishedAt: '2026-07-28T01:01:00Z',
    version: 1,
  }

  const citationStyle = {
    id: 'citation-style-version-1',
    styleCode: 'HOSPITAL_GBT',
    styleName: '医院 GB/T 7714 数字格式',
    versionNo: 1,
    status: 'PUBLISHED',
    layout: 'GB_T_7714',
    authorLimit: 3,
    etAlText: '等',
    includePmid: true,
    includeDoi: true,
    includeEvidenceScope: true,
    evidenceScopeLabel: '摘要级证据',
    createdBy: 'admin-1',
    createdAt: '2026-07-28T01:00:00Z',
    publishedBy: 'admin-1',
    publishedAt: '2026-07-28T01:01:00Z',
    version: 1,
  }

  const documentExport = {
    id: 'export-1',
    projectId: 'project-search-1',
    agentTaskId: 'task-search-1',
    protocolId: 'protocol-1',
    reviewTaskId: 'review-task-1',
    templateVersionId: 'template-version-1',
    citationStyleVersionId: citationStyle.id,
    citationStyleCode: citationStyle.styleCode,
    citationStyleVersion: 'HOSPITAL_GBT/v1',
    status: 'COMPLETED',
    requestedBy: 'user-1',
    confirmedAt: '2026-07-28T01:14:00Z',
    protocolSnapshotSha256: '7'.repeat(64),
    citationSnapshotSha256: '8'.repeat(64),
    citationCount: 1,
    fileName: 'SEARCH-001-研究方案.docx',
    contentType:
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    contentSha256: '9'.repeat(64),
    contentSize: 32456,
    completedAt: '2026-07-28T01:14:00Z',
  }

  const task = () => ({
    id: 'task-search-1',
    projectId: 'project-search-1',
    currentStep: confirmed
      ? (designConfirmed
          ? (reviewStatus === 'APPROVED'
              ? 'STEP_18_EXPORT_DOCUMENT'
              : 'STEP_17_WAIT_EXPERT_REVIEW')
          : 'STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN')
      : 'STEP_07_BUILD_SEARCH_STRATEGY',
    status: exportCompleted ? 'COMPLETED' : 'WAITING_CONFIRMATION',
    input: {
      idea: '研究2型糖尿病患者使用SGLT2抑制剂与eGFR变化的关联',
      directionId: 'DIR-02',
    },
    output: {
      peco: {
        researchQuestion: 'SGLT2抑制剂是否影响12个月eGFR变化？',
        population: '2型糖尿病成人',
        exposure: 'SGLT2抑制剂',
        comparator: '其他降糖药',
        outcome: '12个月eGFR变化',
      },
      searchStrategy: strategy(),
      ...(confirmed
        ? {
            pubmedSearch: {
              schemaVersion: 'pubmed-search-result/v1',
              searchRecordId: 'search-1',
              database: 'PUBMED',
              query: confirmedQuery,
              queryVersion: 'pubmed-query/v1',
              searchedAt: '2026-07-28T01:00:00Z',
              totalResultCount: 1,
              returnedCount: 1,
              records: [{
                pmid: '36331190',
                doi: '10.1056/NEJMoa2204233',
                title: 'Empagliflozin in Patients with Chronic Kidney Disease',
                authors: ['The EMPA-KIDNEY Collaborative Group'],
                journal: 'New England Journal of Medicine',
                publicationDate: '2023-01-12',
                abstractText: 'Abstract text',
                evidenceScope: 'ABSTRACT_ONLY',
                verified: true,
                source: 'PUBMED_EUTILS',
              }],
              rawResponseSha256: 'a'.repeat(64),
              rawContentType: 'application/json',
              toolVersion: 'ncbi-eutils/v1',
              externalRequestCount: 3,
              limitations: ['当前结果仅覆盖 PubMed，未覆盖 CNKI、万方、维普及灰色文献'],
            },
            clinicalTrialsSearch: {
              schemaVersion: 'clinicaltrials-search-result/v1',
              searchRecordId: 'trial-search-1',
              database: 'CLINICAL_TRIALS_GOV',
              sourceType: 'TRIAL_REGISTRY',
              query: '"type 2 diabetes" AND "SGLT2 inhibitor" AND "kidney function"',
              queryVersion: 'clinicaltrials-query/v1',
              searchedAt: '2026-07-28T01:01:00Z',
              totalResultCount: 1,
              returnedCount: 1,
              records: [{
                nctId: 'NCT03594110',
                briefTitle: 'The Study of Heart and Kidney Protection With Empagliflozin',
                officialTitle: 'EMPA-KIDNEY',
                overallStatus: 'COMPLETED',
                studyType: 'INTERVENTIONAL',
                phases: ['PHASE3'],
                conditions: ['Chronic Kidney Disease'],
                interventions: ['DRUG: Empagliflozin'],
                briefSummary: 'Registry summary',
                primaryOutcomes: ['Kidney disease progression'],
                leadSponsor: 'Boehringer Ingelheim',
                startDate: '2019-01-29',
                completionDate: '2022-07-05',
                enrollment: 6609,
                countries: ['United Kingdom'],
                hasResults: true,
                evidenceScope: 'REGISTRY_RESULTS_AVAILABLE',
                verified: true,
                source: 'CLINICAL_TRIALS_GOV',
                linkedPmids: ['36331190'],
              }],
              rawResponseSha256: 'b'.repeat(64),
              rawContentType: 'application/json',
              toolVersion: 'clinicaltrials-api-v2',
              externalRequestCount: 1,
              dataVersion: '2026-07-27',
              cacheHit: false,
              limitations: [
                'ClinicalTrials.gov记录是研究注册信息，不等同于同行评议发表证据',
              ],
            },
            literatureValidation: {
              schemaVersion: 'literature-validation-result/v1',
              validationTaskId: 'validation-1',
              validatedAt: '2026-07-28T01:02:00Z',
              totalCount: 1,
              verifiedCount: 1,
              metadataDifferenceCount: 0,
              mismatchCount: 0,
              crossrefNotFoundCount: 0,
              doiNotAvailableCount: 0,
              citations: [{
                pmid: '36331190',
                doi: '10.1056/nejmoa2204233',
                status: 'VERIFIED',
                validationSource: 'CROSSREF',
                fieldChecks: [
                  {
                    field: 'title',
                    status: 'MATCH',
                    pubmedValue: 'Empagliflozin in Patients with Chronic Kidney Disease',
                    crossrefValue: 'Empagliflozin in Patients with Chronic Kidney Disease',
                  },
                ],
                crossrefMetadata: {
                  doi: '10.1056/nejmoa2204233',
                  title: 'Empagliflozin in Patients with Chronic Kidney Disease',
                  authors: ['The EMPA-KIDNEY Collaborative Group'],
                  journal: 'New England Journal of Medicine',
                  publicationDate: '2023-01-12',
                  type: 'journal-article',
                  publisher: 'Massachusetts Medical Society',
                },
                message: 'Crossref DOI 与 PubMed 核心元数据一致',
              }],
              evidenceLinks: [{
                nctId: 'NCT03594110',
                pmid: '36331190',
                relationship: 'REGISTRY_REFERENCES_PUBLICATION',
                status: 'RESOLVED',
              }],
              rawResponseSha256: 'c'.repeat(64),
              rawContentType: 'application/json',
              toolVersion: 'crossref-rest/v1',
              externalRequestCount: 1,
              cacheHitCount: 0,
              limitations: ['Crossref 元数据校验不能替代全文审阅'],
            },
            similarResearchAnalysis: {
              schemaVersion: 'similar-research-analysis-result/v1',
              analysisTaskId: 'similar-analysis-1',
              analyzedAt: '2026-07-28T01:03:00Z',
              researchQuestion: 'SGLT2抑制剂是否影响12个月eGFR变化？',
              databaseScope: ['PUBMED', 'CLINICAL_TRIALS_GOV', 'CROSSREF'],
              analyzedSourceCount: 2,
              excludedCitationCount: 0,
              highSimilarityCount: 0,
              moderateSimilarityCount: 2,
              lowSimilarityCount: 0,
              similarResearch: [
                {
                  sourceType: 'PUBMED_ARTICLE',
                  sourceIdentifier: '36331190',
                  pmid: '36331190',
                  doi: '10.1056/nejmoa2204233',
                  title: 'Empagliflozin in Patients with Chronic Kidney Disease',
                  publicationOrCompletionDate: '2023-01-12',
                  similarityScore: 55,
                  similarityTier: 'MODERATE',
                  verificationStatus: 'VERIFIED',
                  evidenceScope: 'ABSTRACT_ONLY',
                  dimensions: [
                    {
                      dimension: 'EXPOSURE',
                      matched: true,
                      weight: 25,
                      matchedTerms: ['empagliflozin'],
                    },
                    {
                      dimension: 'OUTCOME',
                      matched: true,
                      weight: 30,
                      matchedTerms: ['kidney'],
                    },
                  ],
                  differences: ['研究对象未在当前来源元数据/摘要中匹配'],
                  linkedSourceIdentifiers: ['NCT03594110'],
                },
                {
                  sourceType: 'TRIAL_REGISTRY',
                  sourceIdentifier: 'NCT03594110',
                  nctId: 'NCT03594110',
                  title: 'The Study of Heart and Kidney Protection With Empagliflozin',
                  publicationOrCompletionDate: '2022-07-05',
                  similarityScore: 55,
                  similarityTier: 'MODERATE',
                  verificationStatus: 'REGISTRY_VERIFIED',
                  evidenceScope: 'REGISTRY_RESULTS_AVAILABLE',
                  dimensions: [
                    {
                      dimension: 'EXPOSURE',
                      matched: true,
                      weight: 25,
                      matchedTerms: ['empagliflozin'],
                    },
                    {
                      dimension: 'OUTCOME',
                      matched: true,
                      weight: 30,
                      matchedTerms: ['kidney'],
                    },
                  ],
                  differences: ['研究对象未在当前来源元数据/摘要中匹配'],
                  linkedSourceIdentifiers: ['PMID:36331190'],
                },
              ],
              potentialResearchGaps: [{
                code: 'POPULATION_EVIDENCE_GAP',
                statement: '当前已验证检索结果中未发现明确匹配研究对象的来源，建议扩展检索并人工复核。',
                basis: '仅依据当前检索与摘要级内容形成，不能据此证明创新。',
                basisSourceIdentifiers: ['36331190', 'NCT03594110'],
              }],
              conclusion: '基于当前检索数据库、检索式和检索日期，暂未发现高度相似研究；'
                + '该结论不代表完成了全部数据库和灰色文献检索。',
              inputSha256: 'd'.repeat(64),
              algorithmVersion: 'deterministic-peco-overlap/v1',
              limitations: ['相似度不是创新性评分'],
            },
            observationalDesignRecommendation: designRecommendation(),
            ...(designConfirmed
              ? {
                  protocolDraft: protocolDraft(),
                  statisticalAnalysisDraft: statisticalAnalysisDraft(),
                  claimCitationValidation: claimCitationValidation(),
                  strobeCompletenessCheck: strobeCompletenessCheck(),
                  expertReview: expertReview(),
                  ...(exportCompleted
                    ? {
                        documentExport: {
                          schemaVersion: 'document-export/v2',
                          exportId: documentExport.id,
                          templateVersionId: documentExport.templateVersionId,
                          templateCode: documentTemplate.templateCode,
                          templateVersionNo: documentTemplate.versionNo,
                          citationStyleVersionId: citationStyle.id,
                          citationStyleCode: documentExport.citationStyleCode,
                          citationStyleVersion: documentExport.citationStyleVersion,
                          citationLayout: citationStyle.layout,
                          citationCount: documentExport.citationCount,
                          contentSha256: documentExport.contentSha256,
                          contentSize: documentExport.contentSize,
                          fileName: documentExport.fileName,
                          completedAt: documentExport.completedAt,
                        },
                      }
                    : {}),
                }
              : {}),
          }
        : {}),
    },
    version: confirmed ? 8 : 7,
    createdAt: '2026-07-27T10:00:00Z',
  })

  const ok = (data: unknown) => ({
    success: true,
    data,
    error: null,
    timestamp: '2026-07-27T10:00:00Z',
    traceId: 'search-strategy-e2e',
  })

  await page.route(/^http:\/\/127\.0\.0\.1:4173\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname

    if (path === '/api/auth/me') {
      await route.fulfill({
        json: ok({
          userId: 'user-1',
          hospitalId: 'hospital-1',
          username: 'doctor-search',
          roles: ['DOCTOR', 'EXPERT'],
          forcePasswordChange: false,
        }),
      })
      return
    }
    if (path === '/api/research/projects') {
      await route.fulfill({
        json: ok([{
          id: 'project-search-1',
          code: 'SEARCH-001',
          name: '检索策略课题',
          version: 0,
        }]),
      })
      return
    }
    if (path === '/api/document-templates') {
      await route.fulfill({ json: ok([documentTemplate]) })
      return
    }
    if (path === '/api/citation-styles') {
      await route.fulfill({ json: ok([citationStyle]) })
      return
    }
    if (path === '/api/research/projects/project-search-1/members') {
      await route.fulfill({
        json: ok([{ userId: 'user-1', username: 'doctor-search', role: 'OWNER' }]),
      })
      return
    }
    if (path === '/api/agent/tasks' && request.method() === 'GET') {
      await route.fulfill({ json: ok([task()]) })
      return
    }
    if (path === '/api/agent/tasks/task-search-1/clarifications') {
      await route.fulfill({ json: ok([]) })
      return
    }
    if (path === '/api/agent/tasks/task-search-1/confirm-search-strategy') {
      const payload = request.postDataJSON() as { pubmedQuery: string }
      confirmedQuery = payload.pubmedQuery
      confirmed = true
      await route.fulfill({ json: ok(task()) })
      return
    }
    if (path === '/api/agent/tasks/task-search-1/confirm-observational-design') {
      const payload = request.postDataJSON() as {
        studyType: string
        primaryOutcome: string
        authorizeProtocolGeneration: boolean
      }
      expect(payload.studyType).toBe('COHORT')
      expect(payload.primaryOutcome).toBe('12 个月 eGFR 绝对变化')
      expect(payload.authorizeProtocolGeneration).toBe(true)
      designConfirmed = true
      await route.fulfill({ json: ok(task()) })
      return
    }
    if (path === '/api/agent/tasks/task-search-1/expert-review/comments') {
      const payload = request.postDataJSON() as {
        protocolSectionId: string
        protocolSectionVersionNo: number
        commentType: string
        responsibility: string
        content: string
      }
      reviewComments.push({
        id: 'review-comment-1',
        ...payload,
        reviewRoundNo: 1,
        createdBy: 'user-1',
        createdAt: '2026-07-28T01:11:00Z',
      })
      reviewHistory.push({
        id: 'review-action-comment',
        actionType: 'COMMENT_ADDED',
        reviewRoundNo: 1,
        actorUserId: 'user-1',
        summary: `${payload.commentType} 批注`,
        occurredAt: '2026-07-28T01:11:00Z',
      })
      await route.fulfill({ json: ok(expertReview()) })
      return
    }
    if (path === '/api/agent/tasks/task-search-1/expert-review/decision') {
      const payload = request.postDataJSON() as {
        decision: string
        responsibility: string
        summary: string
        expectedVersion: number
      }
      expect(payload.decision).toBe('APPROVE')
      if (payload.responsibility === 'MEDICAL_REVIEW') {
        expect(payload.expectedVersion).toBe(0)
        medicalApproved = true
        reviewVersion = 1
      } else {
        expect(payload.responsibility).toBe('STATISTICAL_REVIEW')
        expect(payload.expectedVersion).toBe(1)
        statisticalApproved = true
        reviewStatus = 'EXPERT_APPROVED'
        reviewVersion = 2
      }
      reviewHistory.push({
        id: `review-action-${payload.responsibility}`,
        actionType: payload.responsibility === 'MEDICAL_REVIEW'
          ? 'MEDICAL_REVIEW_APPROVED' : 'STATISTICAL_REVIEW_APPROVED',
        reviewRoundNo: 1,
        actorUserId: payload.responsibility === 'MEDICAL_REVIEW'
          ? 'medical-user' : 'statistical-user',
        summary: payload.summary,
        occurredAt: '2026-07-28T01:12:00Z',
      })
      await route.fulfill({ json: ok(expertReview()) })
      return
    }
    if (path === '/api/agent/tasks/task-search-1/expert-review/owner-confirmation') {
      const payload = request.postDataJSON() as { expectedVersion: number }
      expect(payload.expectedVersion).toBe(2)
      reviewStatus = 'APPROVED'
      reviewVersion = 3
      reviewHistory.push({
        id: 'review-action-owner',
        actionType: 'OWNER_CONFIRMED',
        reviewRoundNo: 1,
        actorUserId: 'owner-user',
        summary: '课题负责人确认专家审核结论并锁定当前章节版本。',
        occurredAt: '2026-07-28T01:13:00Z',
      })
      await route.fulfill({ json: ok(expertReview()) })
      return
    }
    if (path === '/api/agent/tasks/task-search-1/expert-review') {
      await route.fulfill({ json: ok(expertReview()) })
      return
    }
    if (path === '/api/agent/tasks/task-search-1/document-export') {
      if (request.method() === 'POST') {
        const payload = request.postDataJSON() as {
          templateVersionId: string
          citationStyleVersionId: string
          confirmReviewedContent: boolean
        }
        expect(payload.templateVersionId).toBe(documentTemplate.id)
        expect(payload.citationStyleVersionId).toBe(citationStyle.id)
        expect(payload.confirmReviewedContent).toBe(true)
        exportCompleted = true
        await route.fulfill({ json: ok(documentExport) })
      } else if (exportCompleted) {
        await route.fulfill({ json: ok(documentExport) })
      } else {
        await route.fulfill({ status: 404, json: ok(null) })
      }
      return
    }
    if (path === '/api/agent/tasks/task-search-1/events') {
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: 'retry: 60000\n\n',
      })
      return
    }
    if (path === '/api/agent/tasks/task-search-1') {
      await route.fulfill({ json: ok(task()) })
      return
    }
    await route.fulfill({ status: 404, json: ok(null) })
  })

  await page.goto('/workspace')
  await expect(page.getByText('当前用户：doctor-search')).toBeVisible()
  await page.getByRole('button', { name: '成员/文件' }).click()

  await expect(page.getByText('请复核并确认 PubMed 检索策略')).toBeVisible()
  await expect(page.getByText('pubmed-query/v1')).toBeVisible()
  await expect(page.getByText(/未覆盖 CNKI、万方、维普/)).toBeVisible()

  const editor = page.getByLabel('可修订的 PubMed 检索式')
  await editor.fill(`${generatedQuery}\nNOT animals[MeSH Terms]`)
  await page.getByRole('button', { name: '确认检索策略并完成本步骤' }).click()

  await expect(page.getByText('STEP12 观察性研究设计推荐与人工确认')).toBeVisible()
  await expect(page.getByText('observational-design-rules/v1')).toBeVisible()
  await expect(page.getByText('时间零点或不死时间偏倚')).toBeVisible()
  await page.getByText(
    '我已复核研究类型与主要终点，并授权进入正式研究方案生成',
    { exact: true },
  ).click()
  await page.getByRole('button', { name: '确认设计并授权下一阶段' }).click()

  await expect(page.getByText('已确认研究类型')).toBeVisible()
  await expect(page.getByText('已授权')).toBeVisible()
  await expect(page.getByText('STEP13 分章节观察性研究方案草案')).toBeVisible()
  await expect(page.getByText('deterministic-observational-protocol/v1')).toBeVisible()
  await expect(page.getByText('13. 统计分析 · v2')).toBeVisible()
  await page.getByText('13. 统计分析 · v2').click()
  await expect(page.getByText(/MISSING_NEEDS_INPUT/).first()).toBeVisible()
  await expect(page.getByText('STEP14 统计分析计划草案')).toBeVisible()
  await expect(page.getByText('deterministic-observational-statistics/v1')).toBeVisible()
  await expect(page.getByText('NEEDS_EXPERT_CONFIRMATION')).toBeVisible()
  await expect(page.getByText('样本量计算参数清单')).toBeVisible()
  await expect(page.getByText('MISSING_NEEDS_INPUT').first()).toBeVisible()
  await expect(page.getByText(/不计算、不猜测也不承诺最终样本量/)).toBeVisible()
  await expect(page.getByText('STEP15 研究主张—引用依据验证')).toBeVisible()
  await expect(page.getByText('deterministic-claim-citation-linker/v1')).toBeVisible()
  await expect(page.getByText(/ABSTRACT_ONLY 表示已定位候选摘要依据/)).toBeVisible()
  await expect(page.getByText(
    'RESEARCH_STATUS · 主张 1 · ABSTRACT_ONLY',
  )).toBeVisible()
  await page.getByText('RESEARCH_STATUS · 主张 1 · ABSTRACT_ONLY').click()
  await expect(page.getByText('PUBMED_ABSTRACT')).toBeVisible()
  await expect(page.getByText(/Empagliflozin was evaluated/)).toBeVisible()
  const researchStatusClaim = page.locator('.el-collapse-item').filter({
    hasText: 'RESEARCH_STATUS · 主张 1 · ABSTRACT_ONLY',
  })
  await expect(researchStatusClaim.getByText('VERIFIED', { exact: true })).toBeVisible()
  await expect(page.getByText(
    'RESEARCH_GAP · 主张 1 · NEEDS_EXPERT_REVIEW',
  )).toBeVisible()
  await page.getByText('RESEARCH_GAP · 主张 1 · NEEDS_EXPERT_REVIEW').click()
  await expect(page.getByText(/当前证据不足/)).toBeVisible()
  await expect(page.getByText('STEP16 STROBE 报告完整性预检查')).toBeVisible()
  await expect(page.getByText(/不是研究质量评分工具/)).toBeVisible()
  await expect(page.getByText('deterministic-strobe-2007-precheck/v1')).toBeVisible()
  await expect(page.getByText('STROBE-10 · 方法 · MISSING')).toBeVisible()
  await page.getByText('STROBE-10 · 方法 · MISSING').click()
  const strobeStudySize = page.locator('.el-collapse-item').filter({
    hasText: 'STROBE-10 · 方法 · MISSING',
  })
  await expect(strobeStudySize.getByText('说明研究样本量的形成依据')).toBeVisible()
  await expect(strobeStudySize.getByText(/当前方案尚未覆盖该条目/)).toBeVisible()
  await expect(page.getByText(
    'STROBE-13 · 结果与讨论 · NEEDS_EXPERT_REVIEW',
  )).toBeVisible()
  await expect(page.getByRole('link', { name: 'STROBE 官方检查表' }))
    .toHaveAttribute('href', 'https://www.strobe-statement.org/checklists/')
  await expect(page.getByText('STEP17 专家审核工作台')).toBeVisible()
  await expect(page.getByText(/必须由三个不同账号完成/)).toBeVisible()
  await page.getByPlaceholder('写明具体问题、修改要求和确认依据')
    .fill('请核对研究背景引用和统计分析章节。')
  await page.getByRole('button', { name: '保存医学审核批注' }).click()
  await expect(page.getByText('请核对研究背景引用和统计分析章节。')).toBeVisible()
  await page.getByPlaceholder('填写审核总结；退回修改前至少需要一条定位批注')
    .fill('医学审核通过。')
  await page.getByRole('button', { name: '医学审核通过' }).click()
  await expect(page.getByText('医学审核通过。').first()).toBeVisible()
  await page.getByRole('combobox', { name: '审核职责' }).click({ force: true })
  await page.getByRole('option', { name: '统计审核' }).click()
  await page.getByPlaceholder('填写审核总结；退回修改前至少需要一条定位批注')
    .fill('统计审核通过。')
  await page.getByRole('button', { name: '统计审核通过' }).click()
  await expect(page.getByText('统计审核通过。').first()).toBeVisible()
  await expect(page.getByRole('button', { name: '课题负责人确认并锁定当前章节版本' }))
    .toBeVisible()
  await page.getByRole('button', { name: '课题负责人确认并锁定当前章节版本' }).click()
  await expect(page.getByText('APPROVED', { exact: true })).toBeVisible()
  await expect(page.getByText(/OWNER_CONFIRMED/)).toBeVisible()
  await expect(page.getByText('STEP18 受控 Word 导出')).toBeVisible()
  await expect(page.getByText('正式可用')).toHaveCount(2)
  await page.getByText(
    '我确认导出内容来自专家通过、课题负责人确认并锁定的方案版本',
    { exact: true },
  ).click()
  await page.getByRole('button', { name: '确认并生成 Word 文档' }).click()
  await expect(page.getByText('HOSPITAL_GBT · HOSPITAL_GBT/v1')).toBeVisible()
  await expect(page.getByRole('link', { name: 'SEARCH-001-研究方案.docx' }))
    .toHaveAttribute('href', '/api/document-exports/export-1/download')
  await page.getByText('18. 参考文献 · v1').click()
  await expect(page.getByText(/PMID:36331190/).last()).toBeVisible()
  await expect(page.getByText('CONFIRMED', { exact: true })).toBeVisible()
  await expect(page.getByText(/NOT animals\[MeSH Terms\]/)).toBeVisible()
  await expect(page.getByText('36331190').first()).toBeVisible()
  await expect(page.getByText(
    'Empagliflozin in Patients with Chronic Kidney Disease',
  ).first()).toBeVisible()
  await expect(page.getByText('ncbi-eutils/v1')).toBeVisible()
  await expect(page.getByText('NCT03594110').first()).toBeVisible()
  await expect(page.getByText(
    'The Study of Heart and Kidney Protection With Empagliflozin',
  ).first()).toBeVisible()
  await expect(page.getByText('clinicaltrials-api-v2')).toBeVisible()
  await expect(page.getByText('STEP10 文献真实性与跨来源关联验证')).toBeVisible()
  await expect(page.getByText('VERIFIED').first()).toBeVisible()
  await expect(page.getByText('REGISTRY_REFERENCES_PUBLICATION')).toBeVisible()
  await expect(page.getByText('crossref-rest/v1')).toBeVisible()
  await expect(page.getByText('STEP11 相似研究与潜在研究空白')).toBeVisible()
  await expect(page.getByText('deterministic-peco-overlap/v1')).toBeVisible()
  await expect(page.getByText('POPULATION_EVIDENCE_GAP')).toBeVisible()
  await expect(page.getByText(/不代表完成了全部数据库和灰色文献检索/)).toBeVisible()
  expect(confirmedQuery).toContain('NOT animals[MeSH Terms]')
})
