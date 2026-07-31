import { expect, test, type Page } from '@playwright/test'

const projectKey = 'prj_9123456789ABCDEFGHJKMNPQRS'

function meta(version: number) {
  return {
    readModelVersion: version,
    asOf: '2026-07-30T10:00:00Z',
    latestEventId: version,
  }
}

function allowed(code: string, section: string) {
  return {
    code,
    label: code,
    enabled: true,
    reasonCode: null,
    reason: null,
    targetRoute: `/projects/${projectKey}/${section}`,
  }
}

function artifact(
  sectionCode: string,
  title: string,
  content: Record<string, unknown>,
  actions: ReturnType<typeof allowed>[] = [],
) {
  return {
    projectKey,
    sectionCode,
    title,
    status: { code: 'WAITING_USER', label: '需要人工处理' },
    content,
    allowedActions: actions,
    disclaimer: '当前内容仅用于匿名科研草案，不构成诊疗、伦理或正式批准。',
  }
}

async function mockArtifacts(page: Page) {
  let version = 20
  let searchConfirmed = false
  let protocolContent = '匿名研究背景初稿'
  let reviewCommentAdded = false
  let exportCompleted = false
  let designAdviceCreated = false
  let modelCandidateCreated = false
  let modelCandidateReviewed = false
  let modelCandidateApplied = false

  const summary = () => ({
    projectKey,
    displayName: 'V2 匿名科研课题',
    businessStatus: { code: 'IN_PROGRESS', label: '编制中' },
    currentStage: {
      code: 'PROTOCOL',
      label: '研究方案',
      status: 'WAITING_USER',
      summary: '等待人工处理',
      targetRoute: `/projects/${projectKey}/protocol`,
      blockedReasonCodes: [],
      completedAt: null,
    },
    progress: { completed: 5, total: 9, percent: 56 },
    nextAction: allowed('UPDATE_PROTOCOL_SECTION', 'protocol'),
    allowedActions: [],
    blockedReasons: [],
    pendingTodoCount: 1,
    lastUpdatedAt: '2026-07-30T10:00:00Z',
  })

  const stages = [
    ['RESEARCH_IDEA', '研究构想', 'idea'],
    ['RESEARCH_DIRECTION', '研究方向', 'direction'],
    ['EVIDENCE', '医学证据', 'evidence'],
    ['RESEARCH_DESIGN', '研究设计', 'design'],
    ['PROTOCOL', '研究方案', 'protocol'],
    ['STATISTICS', '统计分析', 'statistics'],
    ['QUALITY', '质量检查', 'quality'],
    ['INTERNAL_REVIEW', '内部审核', 'review'],
    ['DRAFT_EXPORT', '科研草案导出', 'export'],
  ].map(([code, label, section]) => ({
    code,
    label,
    status: 'WAITING_USER',
    summary: `${label}需要人工处理`,
    targetRoute: `/projects/${projectKey}/${section}`,
    blockedReasonCodes: [],
    completedAt: null,
  }))

  const evidence = () => artifact(
    'EVIDENCE',
    '医学证据',
    {
      searchStrategy: {
        originalResearchQuestion: '匿名暴露与肾功能变化是否相关？',
        databases: ['PUBMED', 'CLINICAL_TRIALS_GOV'],
        pubmedQuery: searchConfirmed
          ? 'anonymous cohort NOT animals[MeSH Terms]'
          : 'anonymous cohort',
      },
      ...(searchConfirmed
        ? {
            pubmed: {
              returnedCount: 1,
              totalResultCount: 1,
              records: [{
                pmid: '36331190',
                title: '匿名队列研究元数据',
                journal: 'Example Journal',
                publicationDate: '2025-01-01',
                evidenceScope: '摘要级证据',
              }],
            },
          }
        : {}),
    },
    searchConfirmed
      ? []
      : [allowed('CONFIRM_SEARCH_STRATEGY', 'evidence')],
  )

  const design = artifact(
    'RESEARCH_DESIGN',
    '研究设计',
    {
      recommendation: {
        recommendedStudyType: 'COHORT',
        primaryOutcomeCandidate: '12 个月匿名结局变化',
        readyForProtocolDraft: true,
        alternatives: [{
          rank: 1,
          studyType: 'COHORT',
          rationale: '时间顺序与当前匿名数据匹配。',
          biasRisks: ['残余混杂'],
          missingFields: [],
        }],
      },
    },
    [
      allowed('CONFIRM_OBSERVATIONAL_DESIGN', 'design'),
      allowed('REQUEST_DESIGN_MODEL_ADVICE', 'design'),
    ],
  )

  const protocol = () => artifact(
    'PROTOCOL',
    '研究方案',
    {
      protocol: {
        title: '匿名观察性研究方案草案',
        studyType: 'COHORT',
        sections: [{
          sectionKey: 'sec_PUBLICBACKGROUND',
          sectionCode: 'BACKGROUND',
          title: '研究背景',
          sortOrder: 1,
          versionNo: 2,
          content: protocolContent,
          issuesToConfirm: [],
          versionHistory: [
            {
              historyKey: 'his_PUBLIC1',
              revisionNo: 1,
              origin: 'AGENT_DETERMINISTIC',
              changeReason: '初始生成',
              createdAt: '2026-07-30T09:00:00Z',
              content: '匿名研究背景初稿',
            },
            {
              historyKey: 'his_PUBLIC2',
              revisionNo: 2,
              origin: 'HUMAN',
              changeReason: '医学审核修订',
              createdAt: '2026-07-30T09:30:00Z',
              content: protocolContent,
            },
          ],
        }],
      },
    },
    [
      allowed('UPDATE_PROTOCOL_SECTION', 'protocol'),
      allowed('REGENERATE_PROTOCOL_SECTION', 'protocol'),
      allowed('SUBMIT_PROTOCOL_REVISION', 'protocol'),
      allowed('GENERATE_PROTOCOL_SECTION_CANDIDATE', 'protocol'),
      allowed('REVIEW_PROTOCOL_SECTION_CANDIDATE', 'protocol'),
      allowed('APPLY_PROTOCOL_SECTION_CANDIDATE', 'protocol'),
    ],
  )

  const statistics = artifact(
    'STATISTICS',
    '统计分析',
    {
      statisticalDraft: {
        primaryOutcome: '12 个月匿名结局变化',
        primaryAnalysisCandidates: ['多变量线性回归'],
        sampleSizeParameters: [{
          code: 'EFFECT_SIZE',
          label: '效应量',
          valueStatus: 'NEEDS_INPUT',
          rationale: '需要统计专家提供参数',
        }],
      },
    },
  )

  const quality = artifact(
    'QUALITY',
    '质量检查',
    {
      claimCitation: {
        claims: [{
          claimKey: 'clm_PUBLIC1',
          claimText: '摘要级证据支持该背景主张。',
          supportStatus: 'PARTIALLY_COVERED',
        }],
      },
      strobe: {
        coveredCount: 1,
        partiallyCoveredCount: 1,
        missingCount: 1,
        needsExpertReviewCount: 1,
        items: [{
          checkItemKey: 'chk_PUBLIC1',
          itemCode: 'STROBE-01',
          status: 'MISSING',
          requirementSummary: '标题中说明研究设计',
          suggestion: '补充观察性研究设计',
        }],
      },
    },
  )

  const review = () => artifact(
    'INTERNAL_REVIEW',
    '内部审核',
    {
      review: {
        reviewRoundNo: 2,
        version: reviewCommentAdded ? 1 : 0,
        medicalDecision: null,
        statisticalDecision: null,
        ownerConfirmed: false,
        commentTargets: [{
          targetType: 'PROTOCOL_SECTION',
          targetKey: 'sec_PUBLICBACKGROUND',
          targetVersion: 2,
          label: '研究背景 v2',
        }],
        comments: reviewCommentAdded
          ? [{
              commentKey: 'cmt_PUBLIC1',
              targetType: 'PROTOCOL_SECTION',
              targetVersion: 2,
              commentType: 'MEDICAL',
              responsibility: 'MEDICAL_REVIEW',
              content: '请补充匿名纳入标准。',
            }]
          : [],
        history: [
          {
            actionType: 'REVIEW_OPENED',
            summary: '已发起第二轮内部审核',
            occurredAt: '2026-07-30T10:00:00Z',
          },
          ...(reviewCommentAdded
            ? [{
                actionType: 'COMMENT_ADDED',
                summary: '医学审核批注',
                occurredAt: '2026-07-30T10:05:00Z',
              }]
            : []),
        ],
      },
    },
    [
      allowed('ADD_INTERNAL_REVIEW_COMMENT', 'review'),
      allowed('SUBMIT_MEDICAL_REVIEW', 'review'),
    ],
  )

  const exportView = () => artifact(
    'DRAFT_EXPORT',
    '科研草案导出',
    exportCompleted
      ? {
          completedExport: {
            fileName: '匿名科研草案.docx',
            contentSize: 4,
            downloadUrl: `/api/research/projects/${projectKey}/exports/exp_PUBLIC/download`,
          },
        }
      : {
          templates: [{
            templateKey: 'tpl_PUBLIC1',
            templateName: '观察性研究默认模板',
            versionNo: 1,
          }],
          citationStyles: [{
            styleKey: 'style_PUBLIC1',
            styleName: '机构数字引用格式',
            versionNo: 1,
          }],
        },
    exportCompleted
      ? []
      : [allowed('EXPORT_RESEARCH_DRAFT', 'export')],
  )

  const artifactByPath: Record<string, () => ReturnType<typeof artifact>> = {
    evidence,
    design: () => design,
    protocol,
    statistics: () => statistics,
    quality: () => quality,
    'internal-review': review,
    'draft-export': exportView,
  }

  await page.route('http://127.0.0.1:4174/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/auth/me') {
      await route.fulfill({
        json: {
          success: true,
          data: {
            userId: 'usr_PUBLIC',
            hospitalId: 'hsp_PUBLIC',
            username: 'doctor-v2',
            roles: ['DOCTOR'],
            forcePasswordChange: false,
          },
          error: null,
        },
      })
      return
    }
    if (path.endsWith('/workspace-summary')) {
      await route.fulfill({ json: { data: summary(), meta: meta(version) } })
      return
    }
    if (path.endsWith('/stages')) {
      await route.fulfill({ json: { data: stages, meta: meta(version) } })
      return
    }
    if (path.endsWith('/todos')) {
      await route.fulfill({ json: { data: [], meta: meta(version) } })
      return
    }
    if (path.endsWith('/events')) {
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: 'retry: 60000\n\n',
      })
      return
    }
    if (path.endsWith('/exports/exp_PUBLIC/download')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        headers: {
          'Content-Disposition': 'attachment; filename="draft.docx"',
        },
        body: Buffer.from('PK-test'),
      })
      return
    }
    if (path.endsWith('/design/model-advice')) {
      await route.fulfill({
        json: {
          data: designAdviceCreated
            ? [{
                adviceKey: 'dadv_PUBLIC',
                ruleVersion: '观察性研究规则 v1',
                ruleRecommendedStudyType: 'COHORT',
                modelSelectedStudyType: 'COHORT',
                advice: {
                  schemaVersion: 'observational-design-model-advice/v1',
                  selectedStudyType: 'COHORT',
                  alignment: 'ALIGNED',
                  rationale: '模型建议与版本化规则结果一致。',
                  biasConsiderations: ['残余混杂'],
                  missingFields: [],
                  suggestedConfirmations: ['确认研究类型'],
                  limitations: ['仅供科研设计讨论，未经伦理和科研管理审批'],
                  advisoryOnly: true,
                },
                conflicts: [],
                status: 'ALIGNED',
                advisoryOnly: true,
                createdAt: '2026-07-30T10:00:00Z',
              }]
            : [],
          meta: meta(version),
        },
      })
      return
    }
    if (path.endsWith('/protocol/model-candidates')) {
      await route.fulfill({
        json: {
          data: modelCandidateCreated
            ? [{
                candidateKey: 'cand_PUBLIC',
                sectionKey: 'sec_PUBLICBACKGROUND',
                sectionCode: 'BACKGROUND',
                baseVersionNo: 2,
                status: modelCandidateApplied ? 'APPLIED' : 'VALIDATED',
                content: '匿名研究背景模型辅助候选。',
                usedEvidenceIdentifiers: [],
                issuesToConfirm: ['由医学与统计专家确认'],
                generatedAt: '2026-07-30T10:00:00Z',
                version: modelCandidateApplied ? 1 : 0,
                appliedVersionNo: modelCandidateApplied ? 3 : null,
              }]
            : [],
          meta: meta(version),
        },
      })
      return
    }
    if (path.endsWith('/protocol/model-reviews')) {
      await route.fulfill({
        json: {
          data: modelCandidateReviewed
            ? [{
                reviewKey: 'mrev_PUBLIC',
                candidateKey: 'cand_PUBLIC',
                severity: 'LOW',
                issues: [],
                summary: '未发现结构化阻断，仍需人工确认。',
                advisoryOnly: true,
                createdAt: '2026-07-30T10:01:00Z',
              }]
            : [],
          meta: meta(version),
        },
      })
      return
    }
    if (path.endsWith('/model-governance')) {
      await route.fulfill({
        json: {
          data: {
            configuredMode: 'mock',
            externalModelEnabled: false,
            externalModelOffByDefault: true,
            routes: [{
              logicalModelTypeLabel: '标准科研生成',
              provider: 'mock',
              modelName: 'deterministic-test',
              policyVersion: 'mock-routing/v2',
              routeReason: 'DETERMINISTIC_TEST_DEFAULT',
              priced: false,
            }],
            budget: {
              currency: 'USD',
              maxCallCostMicros: 250000,
              maxProjectCostMicros: 5000000,
              status: 'ACTIVE',
              version: 0,
              persisted: true,
              committedOrReservedCostMicros: 0,
              activeReservationCostMicros: 0,
              remainingCostMicros: 5000000,
            },
            budgetPolicy: '外部调用前原子预留预算。',
            disclaimer: '仅供科研设计讨论，未经伦理和科研管理审批',
          },
          meta: meta(version),
        },
      })
      return
    }
    if (path.endsWith('/model-usage')) {
      await route.fulfill({
        json: {
          data: {
            callCount: 1,
            succeededCostMicros: 0,
            activeReservationCostMicros: 0,
            committedOrReservedCostMicros: 0,
            calls: [{
              callKey: 'mcall_PUBLIC',
              logicalModelTypeLabel: '标准科研生成',
              provider: 'mock',
              modelName: 'deterministic-test',
              status: 'SUCCEEDED',
              statusLabel: '调用成功',
              usageSource: 'SYNTHETIC_TEST',
              costStatus: 'TEST_ONLY',
              costStatusLabel: '合成测试调用，不计真实费用',
              startedAt: '2026-07-30T10:00:00Z',
            }],
            disclaimer: '仅供科研设计讨论，未经伦理和科研管理审批',
          },
          meta: meta(version),
        },
      })
      return
    }
    const artifactName = Object.keys(artifactByPath).find(
      name => path.endsWith(`/${name}`),
    )
    if (artifactName && request.method() === 'GET') {
      await route.fulfill({
        json: {
          data: artifactByPath[artifactName](),
          meta: meta(version),
        },
      })
      return
    }
    if (path.includes('/actions/') && request.method() === 'POST') {
      expect(request.headers()['if-match']).toBe(`"rmv-${version}"`)
      const payload = request.postDataJSON() as Record<string, unknown>
      if (path.endsWith('/CONFIRM_SEARCH_STRATEGY')) {
        searchConfirmed = true
      }
      if (path.endsWith('/UPDATE_PROTOCOL_SECTION')) {
        protocolContent = String(payload.content)
      }
      if (path.endsWith('/REQUEST_DESIGN_MODEL_ADVICE')) {
        designAdviceCreated = true
      }
      if (path.endsWith('/GENERATE_PROTOCOL_SECTION_CANDIDATE')) {
        modelCandidateCreated = true
      }
      if (path.endsWith('/REVIEW_PROTOCOL_SECTION_CANDIDATE')) {
        modelCandidateReviewed = true
      }
      if (path.endsWith('/APPLY_PROTOCOL_SECTION_CANDIDATE')) {
        modelCandidateApplied = true
      }
      if (path.endsWith('/ADD_INTERNAL_REVIEW_COMMENT')) {
        reviewCommentAdded = true
      }
      if (path.endsWith('/EXPORT_RESEARCH_DRAFT')) {
        expect(payload.templateKey).toBe('tpl_PUBLIC1')
        expect(payload.styleKey).toBe('style_PUBLIC1')
        exportCompleted = true
      }
      version += 1
      await route.fulfill({
        json: { data: summary(), meta: meta(version) },
      })
      return
    }
    await route.fulfill({
      status: 404,
      json: { error: { code: 'NOT_FOUND' } },
    })
  })
}

test('V2 七个制品切片按路由加载并执行公开动作', async ({ page }) => {
  await mockArtifacts(page)

  await page.goto(`/projects/${projectKey}/evidence`)
  await expect(page.getByRole('heading', { name: '检索策略' })).toBeVisible()
  await page.getByLabel('PubMed 检索式').fill(
    'anonymous cohort NOT animals[MeSH Terms]',
  )
  await page.getByRole('button', { name: '确认并执行检索' }).click()
  await expect(page.getByText('匿名队列研究元数据')).toBeVisible()

  await page.goto(`/projects/${projectKey}/design`)
  await expect(
    page.getByRole('heading', { name: '观察性研究设计比较' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '获取只读辅助意见' }).click()
  await expect(page.getByText('模型建议与版本化规则结果一致。')).toBeVisible()
  await page.getByText('我确认进入科研方案草案生成').click()
  await page.getByRole('button', { name: '确认研究设计' }).click()

  await page.goto(`/projects/${projectKey}/protocol`)
  await page.getByText(/1\. 研究背景 · v2/).click()
  await page.getByText('查看 2 个历史版本').click()
  await expect(page.getByText('版本内容对比')).toBeVisible()
  const sectionEditor = page.getByLabel('章节内容')
  await sectionEditor.fill('匿名研究背景人工修订版')
  await page.getByRole('button', { name: '保存为新版本' }).click()
  await expect(sectionEditor).toHaveValue('匿名研究背景人工修订版')
  await page.getByRole('button', { name: '生成本章模型候选' }).click()
  await expect(page.getByText('匿名研究背景模型辅助候选。')).toBeVisible()
  await page.getByRole('button', { name: '使用不同模型辅助复核' }).click()
  await expect(page.getByText('未发现结构化阻断，仍需人工确认。')).toBeVisible()
  await page.getByRole('button', { name: '明确采纳为新版本' }).click()
  await expect(page.getByText(/已采纳/)).toBeVisible()

  await page.goto(`/projects/${projectKey}/models`)
  await expect(page.getByRole('heading', { name: '路由、用量与预算' })).toBeVisible()
  await expect(page.getByText('外部模型默认关闭')).toBeVisible()

  await page.goto(`/projects/${projectKey}/statistics`)
  await expect(
    page.getByRole('heading', { name: '统计分析计划草案' }),
  ).toBeVisible()
  await expect(page.getByText('需要统计专家提供参数')).toBeVisible()

  await page.goto(`/projects/${projectKey}/quality`)
  await expect(page.getByText('STROBE-01 · 缺失')).toBeVisible()

  await page.goto(`/projects/${projectKey}/review`)
  await page.getByLabel('批注内容').fill('请补充匿名纳入标准。')
  await page.getByRole('button', { name: '保存批注' }).click()
  await expect(page.getByText('添加审核批注')).toBeVisible()
  await expect(page.getByText('请补充匿名纳入标准。')).toBeVisible()

  await page.goto(`/projects/${projectKey}/export`)
  await page.getByText('我确认导出的是当前已审核锁定版本').click()
  await page.getByRole('button', { name: '生成科研草案' }).click()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('link', { name: '下载科研草案' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toContain('.docx')

  await expect(page.locator('body')).not.toContainText('STEP_')
  await expect(page.locator('body')).not.toContainText(
    /[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/i,
  )
})
