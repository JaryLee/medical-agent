import { expect, test } from '@playwright/test'

const questions = [
  '研究对象来自门诊、住院还是体检数据库？',
  '暴露和对照如何定义？',
  '主要结局如何定义？',
  '计划观察多长时间？',
]

test('医生可保存多轮澄清并用新答案重新生成方向', async ({ page }) => {
  let currentStep = 'STEP_03_ASK_CLARIFICATION'
  let answers: Record<string, string> = {}
  const rounds: Array<{
    id: string
    roundNo: number
    sourceStep: string
    questions: string[]
    answers: Record<string, string>
    submittedBy: string
    submittedAt: string
  }> = []

  const task = () => ({
    id: 'task-1',
    projectId: 'project-1',
    currentStep,
    status: 'WAITING_CONFIRMATION',
    input: {
      idea: '研究2型糖尿病患者使用SGLT2抑制剂与eGFR变化的关联',
      clarificationAnswers: answers,
    },
    output: {
      clarificationQuestions: questions,
      ...(currentStep === 'STEP_05_CONFIRM_DIRECTION'
        ? {
            directions: [
              {
                id: 'DIR-01',
                title: `横断面研究（第 ${rounds.length} 轮）`,
                recommendedStudyType: 'CROSS_SECTIONAL',
                limitations: ['无法确认时序'],
              },
              {
                id: 'DIR-02',
                title: `队列研究（第 ${rounds.length} 轮）`,
                recommendedStudyType: 'COHORT',
                limitations: ['残余混杂'],
              },
              {
                id: 'DIR-03',
                title: `病例对照研究（第 ${rounds.length} 轮）`,
                recommendedStudyType: 'CASE_CONTROL',
                limitations: ['选择偏倚'],
              },
            ],
          }
        : {}),
    },
    version: rounds.length + 1,
    createdAt: '2026-07-27T10:00:00Z',
  })

  const ok = (data: unknown) => ({
    success: true,
    data,
    error: null,
    timestamp: '2026-07-27T10:00:00Z',
    traceId: 'playwright-test',
  })

  await page.route(/^http:\/\/127\.0\.0\.1:4173\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()

    if (path === '/api/auth/me') {
      await route.fulfill({
        json: ok({
          userId: 'user-1',
          hospitalId: 'hospital-1',
          username: 'doctor-e2e',
          roles: ['DOCTOR'],
          forcePasswordChange: false,
        }),
      })
      return
    }
    if (path === '/api/research/projects') {
      await route.fulfill({
        json: ok([{ id: 'project-1', code: 'E2E-001', name: '多轮澄清课题', version: 0 }]),
      })
      return
    }
    if (path === '/api/research/projects/project-1/members') {
      await route.fulfill({
        json: ok([{ userId: 'user-1', username: 'doctor-e2e', role: 'OWNER' }]),
      })
      return
    }
    if (path === '/api/agent/tasks' && method === 'GET') {
      await route.fulfill({ json: ok([task()]) })
      return
    }
    if (path === '/api/agent/tasks/task-1/clarifications' && method === 'GET') {
      await route.fulfill({ json: ok(rounds) })
      return
    }
    if (path === '/api/agent/tasks/task-1/clarifications' && method === 'POST') {
      const payload = request.postDataJSON() as { answers: Record<string, string> }
      rounds.push({
        id: `round-${rounds.length + 1}`,
        roundNo: rounds.length + 1,
        sourceStep: currentStep,
        questions,
        answers: { ...payload.answers },
        submittedBy: 'user-1',
        submittedAt: `2026-07-27T10:0${rounds.length}:00Z`,
      })
      answers = { ...payload.answers }
      currentStep = 'STEP_05_CONFIRM_DIRECTION'
      await route.fulfill({ json: ok(task()) })
      return
    }
    if (path === '/api/agent/tasks/task-1/events') {
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: 'retry: 60000\n\n',
      })
      return
    }
    if (path === '/api/agent/tasks/task-1') {
      await route.fulfill({ json: ok(task()) })
      return
    }
    await route.fulfill({ status: 404, json: ok(null) })
  })

  await page.goto('/workspace')
  await expect(page.getByText('当前用户：doctor-e2e')).toBeVisible()
  await page.getByRole('button', { name: '成员/文件' }).click()
  await expect(page.getByText('信息不完整，回答以下问题后才会生成研究方向')).toBeVisible()

  const firstRoundInputs = page.locator('.clarification-form textarea')
  for (let index = 0; index < questions.length; index += 1) {
    await firstRoundInputs.nth(index).fill(`第一轮匿名答案 ${index + 1}`)
  }
  await page.getByRole('button', { name: '提交澄清信息并生成方向' }).click()

  await expect(page.getByText('队列研究（第 1 轮）')).toBeVisible()
  await expect(page.getByText(/第 1 轮澄清/)).toBeVisible()

  const secondRoundInputs = page.locator('.clarification-form textarea')
  await secondRoundInputs.first().fill('第二轮修订后的匿名答案')
  await page.getByRole('button', { name: '保存新一轮澄清并重新生成方向' }).click()

  await expect(page.getByText('队列研究（第 2 轮）')).toBeVisible()
  await expect(page.getByText(/第 2 轮澄清/)).toBeVisible()
  await page.getByText(/第 2 轮澄清/).click()
  await expect(page.getByText('第二轮修订后的匿名答案')).toBeVisible()
})
