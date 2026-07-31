import { expect, test, type Page } from '@playwright/test'

const projectKey = 'prj_0123456789ABCDEFGHJKMNPQRS'
const questions = [
  '研究对象来自门诊、住院还是体检数据库？',
  '主要结局如何定义？',
]

type WorkflowState = 'NOT_STARTED' | 'CLARIFICATION' | 'DIRECTION' | 'CONTINUING'

function meta(version: number) {
  return {
    readModelVersion: version,
    asOf: '2026-07-30T08:00:00Z',
    latestEventId: version,
  }
}

function stage(
  code: string,
  label: string,
  route: string,
  status = 'NOT_STARTED',
) {
  return {
    code,
    label,
    status,
    summary: status === 'WAITING_USER'
      ? `${label}需要人工确认。`
      : `${label}尚未开始。`,
    targetRoute: `/projects/${projectKey}/${route}`,
    blockedReasonCodes: [],
    completedAt: null,
  }
}

const stageDefinitions = [
  ['RESEARCH_IDEA', '研究构想', 'idea'],
  ['RESEARCH_DIRECTION', '研究方向', 'direction'],
  ['EVIDENCE', '证据检索与核验', 'evidence'],
  ['STUDY_DESIGN', '研究设计', 'design'],
  ['PROTOCOL', '研究方案', 'protocol'],
  ['STATISTICS', '统计分析', 'statistics'],
  ['QUALITY', '质量与报告规范', 'quality'],
  ['INTERNAL_REVIEW', '内部审核', 'review'],
  ['DRAFT_EXPORT', '科研草案导出', 'export'],
] as const

function action(
  code: string,
  label: string,
  targetRoute: string,
) {
  return {
    code,
    label,
    enabled: true,
    reasonCode: null,
    reason: null,
    targetRoute,
  }
}

async function mockWorkspace(page: Page) {
  page.on('pageerror', (error) => {
    throw error
  })
  page.on('console', (message) => {
    if (message.type() === 'error') {
      throw new Error(
        `Browser console error at ${message.location().url}: ${message.text()}`,
      )
    }
  })
  let workflow: WorkflowState = 'NOT_STARTED'
  let version = 1
  let submittedIdea = ''
  let submittedAnswers: Record<string, string> = {}

  const currentAction = () => {
    if (workflow === 'NOT_STARTED') {
      return action(
        'START_RESEARCH_IDEA',
        '提交研究构想',
        `/projects/${projectKey}/idea`,
      )
    }
    if (workflow === 'CLARIFICATION') {
      return action(
        'SUBMIT_CLARIFICATIONS',
        '补充研究信息',
        `/projects/${projectKey}/idea`,
      )
    }
    if (workflow === 'DIRECTION') {
      return action(
        'CONFIRM_RESEARCH_DIRECTION',
        '确认研究方向',
        `/projects/${projectKey}/direction`,
      )
    }
    return action(
      'CONTINUE_IN_LEGACY_WORKSPACE',
      '在旧版继续当前阶段',
      '/workspace/legacy',
    )
  }

  const currentStage = () =>
    workflow === 'NOT_STARTED' || workflow === 'CLARIFICATION'
      ? stage(
        'RESEARCH_IDEA',
        '研究构想',
        'idea',
        workflow === 'CLARIFICATION' ? 'WAITING_USER' : 'NOT_STARTED',
      )
      : stage(
        'RESEARCH_DIRECTION',
        '研究方向',
        'direction',
        workflow === 'DIRECTION' ? 'WAITING_USER' : 'IN_PROGRESS',
      )

  const summary = () => ({
    projectKey,
    displayName: '糖尿病肾功能匿名研究',
    businessStatus: {
      code: workflow === 'NOT_STARTED' ? 'DRAFT' : 'IN_PROGRESS',
      label: workflow === 'NOT_STARTED' ? '草稿' : '编制中',
    },
    currentStage: currentStage(),
    progress: {
      completed: workflow === 'CONTINUING' ? 1 : 0,
      total: 9,
      percent: workflow === 'CONTINUING' ? 11 : 0,
    },
    nextAction: currentAction(),
    allowedActions: [
      currentAction(),
      ...(workflow !== 'NOT_STARTED' && workflow !== 'CONTINUING'
        ? [action(
          'CANCEL_RESEARCH_WORKFLOW',
          '取消当前处理',
          `/projects/${projectKey}/overview`,
        )]
        : []),
    ],
    blockedReasons: [],
    pendingTodoCount: workflow === 'CONTINUING' ? 0 : 1,
    lastUpdatedAt: '2026-07-30T08:00:00Z',
  })

  const todo = () => ({
    todoKey: 'todo_0123456789ABCDEFGHJKMNPQRS',
    projectKey,
    todoType: {
      code: currentAction().code,
      label: currentAction().label,
    },
    title: `${currentAction().label}：糖尿病肾功能匿名研究`,
    description: '完成当前人工确认后继续课题。',
    assigneeRole: 'PROJECT_EDITOR',
    targetRoute: currentAction().targetRoute,
    dueAt: null,
    status: 'OPEN',
  })

  const ideaDirection = () => ({
    projectKey,
    workflowStatus: {
      code: workflow === 'NOT_STARTED'
        ? 'NOT_STARTED'
        : workflow === 'CONTINUING'
          ? 'PROCESSING'
          : 'WAITING_USER',
      label: workflow === 'NOT_STARTED'
        ? '尚未提交研究构想'
        : workflow === 'CONTINUING'
          ? '处理中'
          : '等待人工确认',
    },
    idea: workflow === 'NOT_STARTED'
      ? null
      : { content: submittedIdea, statusLabel: '正在完善' },
    currentClarificationQuestions:
      workflow === 'CLARIFICATION' ? questions : [],
    clarificationHistory:
      workflow === 'DIRECTION' || workflow === 'CONTINUING'
        ? [{
            roundNo: 1,
            questions,
            answers: submittedAnswers,
            submittedAt: '2026-07-30T08:05:00Z',
          }]
        : [],
    directionCandidates:
      workflow === 'DIRECTION' || workflow === 'CONTINUING'
        ? {
            candidateSetKey: 'set_0123456789ABCDEFGHJKMNPQRS',
            schemaVersion: 'direction-candidates/v1',
            candidates: [
              {
                directionKey: 'dir_0123456789ABCDEFGHJKMNPQRS',
                title: '基于匿名病历的回顾性队列研究',
                recommendedStudyType: { code: 'COHORT', label: '队列研究' },
                limitations: ['仍可能存在残余混杂'],
                selected: workflow === 'CONTINUING',
              },
              {
                directionKey: 'dir_1123456789ABCDEFGHJKMNPQRS',
                title: '匿名横断面关联研究',
                recommendedStudyType: {
                  code: 'CROSS_SECTIONAL',
                  label: '横断面研究',
                },
                limitations: ['不能确认时间顺序'],
                selected: false,
              },
              {
                directionKey: 'dir_2123456789ABCDEFGHJKMNPQRS',
                title: '匿名病例对照研究',
                recommendedStudyType: {
                  code: 'CASE_CONTROL',
                  label: '病例对照研究',
                },
                limitations: ['存在选择偏倚风险'],
                selected: false,
              },
            ],
          }
        : null,
    allowedActions: summary().allowedActions,
    disclaimer: '当前内容仅用于科研构想，不替代医学、统计或伦理审核。',
  })

  await page.route('http://127.0.0.1:4174/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname

    if (path === '/api/auth/me') {
      await route.fulfill({
        json: {
          success: true,
          data: {
            userId: 'user-e2e',
            hospitalId: 'hospital-e2e',
            username: 'doctor-e2e',
            roles: ['DOCTOR'],
            forcePasswordChange: false,
          },
          error: null,
        },
      })
      return
    }
    if (path === '/api/research/workspace/projects') {
      await route.fulfill({
        json: {
          data: { items: [summary()], nextCursor: null },
          meta: meta(version),
        },
      })
      return
    }
    if (path === '/api/research/todos') {
      await route.fulfill({
        json: {
          data: {
            items: workflow === 'CONTINUING' ? [] : [todo()],
            nextCursor: null,
          },
          meta: meta(version),
        },
      })
      return
    }
    if (path.endsWith('/workspace-summary')) {
      await route.fulfill({ json: { data: summary(), meta: meta(version) } })
      return
    }
    if (path.endsWith('/stages')) {
      const stages = stageDefinitions.map(([code, label, routeName], index) =>
        index === (workflow === 'DIRECTION' || workflow === 'CONTINUING' ? 1 : 0)
          ? currentStage()
          : stage(code, label, routeName),
      )
      await route.fulfill({ json: { data: stages, meta: meta(version) } })
      return
    }
    if (path.endsWith('/todos')) {
      await route.fulfill({
        json: {
          data: workflow === 'CONTINUING' ? [] : [todo()],
          meta: meta(version),
        },
      })
      return
    }
    if (path.endsWith('/idea-direction')) {
      await route.fulfill({
        json: { data: ideaDirection(), meta: meta(version) },
      })
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
    if (path.includes('/actions/') && request.method() === 'POST') {
      expect(request.headers()['idempotency-key']?.length).toBeGreaterThanOrEqual(16)
      expect(request.headers()['if-match']).toBe(`"rmv-${version}"`)
      const payload = request.postDataJSON() as Record<string, unknown>
      if (path.endsWith('/START_RESEARCH_IDEA')) {
        submittedIdea = String(payload.idea)
        workflow = 'CLARIFICATION'
      } else if (path.endsWith('/SUBMIT_CLARIFICATIONS')) {
        submittedAnswers = payload.answers as Record<string, string>
        workflow = 'DIRECTION'
      } else if (path.endsWith('/CONFIRM_RESEARCH_DIRECTION')) {
        expect(String(payload.directionKey)).toMatch(/^dir_/)
        workflow = 'CONTINUING'
      }
      version += 1
      await route.fulfill({ json: { data: summary(), meta: meta(version) } })
      return
    }
    await route.fulfill({ status: 404, json: { error: { code: 'NOT_FOUND' } } })
  })
}

test('医生通过 V2 完成构想、澄清和研究方向确认', async ({ page }) => {
  await mockWorkspace(page)
  await page.goto('/workspace')

  await expect(
    page.getByRole('heading', { name: '从待处理事项继续工作' }),
  ).toBeVisible()
  await expect(page.getByText('糖尿病肾功能匿名研究')).toBeVisible()
  await page.getByRole('link', { name: '提交研究构想' }).click()

  const idea = page.getByLabel('请描述研究对象、关注因素和预期结局')
  await idea.fill('研究匿名2型糖尿病患者用药与肾功能变化的关联')
  await page.reload()
  await expect(idea).toHaveValue(
    '研究匿名2型糖尿病患者用药与肾功能变化的关联',
  )
  await page.getByRole('button', { name: '提交构想并生成澄清问题' }).click()

  for (const question of questions) {
    await page.getByLabel(question).fill('本院匿名回顾性病例数据')
  }
  await page.getByRole('button', { name: '提交全部补充信息' }).click()

  await expect(page.getByText('候选研究方向')).toBeVisible()
  await page.getByText('基于匿名病历的回顾性队列研究').click()
  await page.getByRole('button', { name: '确认所选研究方向' }).click()
  await expect(
    page.getByRole('heading', { name: '处理中', level: 2 }),
  ).toBeVisible()

  await expect(page.locator('body')).not.toContainText('STEP_')
  await expect(page.locator('body')).not.toContainText(
    /[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/i,
  )
  await expect(page).toHaveURL(new RegExp(`/projects/${projectKey}/idea|direction`))
})

test('V2 提供键盘焦点、语义标题和固定旧版回退', async ({ page }) => {
  await mockWorkspace(page)
  await page.goto(`/projects/${projectKey}/overview`)

  await expect(
    page.getByRole('heading', { name: '糖尿病肾功能匿名研究' }),
  ).toBeVisible()
  await expect(page.getByRole('navigation', { name: '课题阶段' })).toBeVisible()
  await expect(page.getByRole('link', { name: /在旧版工作台继续/ })).toBeVisible()

  await page.keyboard.press('Tab')
  const focused = page.locator(':focus')
  await expect(focused).toBeVisible()
})
