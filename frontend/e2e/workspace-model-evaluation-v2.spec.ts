import { expect, test } from '@playwright/test'

test('医院管理员启动匿名合成案例评测并看到双专家门禁', async ({ page }) => {
  let evaluations: Array<Record<string, unknown>> = []
  await page.route('http://127.0.0.1:4174/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/auth/me') {
      await route.fulfill({
        json: {
          success: true,
          data: {
            userId: 'usr_PUBLIC_ADMIN',
            username: 'research-admin',
            roles: ['HOSPITAL_ADMIN'],
            forcePasswordChange: false,
          },
          error: null,
        },
      })
      return
    }
    if (path === '/api/research/model-evaluations'
      && request.method() === 'POST') {
      const created = {
        evaluationKey: 'eval_PUBLIC_BATCH',
        datasetVersion: 'anonymous-research-cases/v1',
        dataClassification: 'SYNTHETIC_ANONYMOUS',
        promptVersion: 'research-idea-analysis/v1',
        routePolicyVersion: 'mock-routing/v2',
        status: 'WAITING_EXPERT_SCORING',
        statusLabel: '等待两名独立专家评分',
        caseCount: 5,
        passedCount: 5,
        cases: [],
        expertScores: [],
        expertScoringRequired: true,
        disclaimer: '仅供科研设计讨论，未经伦理和科研管理审批',
        startedAt: '2026-07-30T10:00:00Z',
        automatedCompletedAt: '2026-07-30T10:00:01Z',
      }
      evaluations = [created]
      await route.fulfill({
        json: {
          data: created,
          meta: {
            readModelVersion: 2,
            asOf: '2026-07-30T10:00:01Z',
            latestEventId: 0,
          },
        },
      })
      return
    }
    if (path === '/api/research/model-evaluations'
      && request.method() === 'GET') {
      await route.fulfill({
        json: {
          data: evaluations,
          meta: {
            readModelVersion: evaluations.length * 2,
            asOf: '2026-07-30T10:00:01Z',
            latestEventId: 0,
          },
        },
      })
      return
    }
    await route.fulfill({
      status: 404,
      json: { error: { code: 'NOT_FOUND' } },
    })
  })

  await page.goto('/model-evaluations')
  await expect(page.getByRole('heading', { name: '匿名模型评测' })).toBeVisible()
  await page.getByRole('button', { name: '启动匿名合成案例评测' }).click()
  await expect(page.getByRole('button', {
    name: /等待两名独立专家评分/,
  })).toBeVisible()
  await expect(page.getByText('自动指标通过 5 / 5；专家评分 0 / 2')).toBeVisible()
  await expect(page.getByText('仅供科研设计讨论，未经伦理和科研管理审批')).toBeVisible()
})
