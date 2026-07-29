import { expect, test, type Page } from '@playwright/test'

const bootstrapUsername = 'deepseek-platform-admin'
const bootstrapPassword = 'DeepSeekAdmin123'
const changedAdminPassword = 'DeepSeekAdmin456'
const doctorUsername = 'deepseek-doctor'
const initialDoctorPassword = 'DeepSeekDoctor123'
const changedDoctorPassword = 'DeepSeekDoctor456'

async function login(page: Page, hospitalCode: string, username: string, password: string) {
  await page.getByLabel('医院编码（平台管理员留空）').fill(hospitalCode)
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

async function changeInitialPassword(page: Page, currentPassword: string, newPassword: string) {
  await expect(page.getByText('首次登录必须修改初始密码')).toBeVisible()
  await page.getByPlaceholder('当前密码').fill(currentPassword)
  await page.getByPlaceholder('新密码（至少12位）').fill(newPassword)
  const [response] = await Promise.all([
    page.waitForResponse((candidate) => (
      candidate.url().includes('/api/auth/change-password')
      && candidate.request().method() === 'POST'
    )),
    page.getByRole('button', { name: '修改密码并注销现有会话' }).click(),
  ])
  expect(
    response.ok(),
    `change-password returned HTTP ${response.status()}: ${await response.text()}`,
  ).toBeTruthy()
  await expect(page.getByText('密码已修改，请重新登录')).toBeVisible()
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible()
}

async function postJson<T>(page: Page, path: string, body: unknown): Promise<T> {
  return page.evaluate(async ({ requestPath, requestBody }) => {
    const token = document.cookie
      .split('; ')
      .find((item) => item.startsWith('XSRF-TOKEN='))
      ?.split('=')[1]
    if (!token) throw new Error('XSRF token is missing')
    const response = await fetch(requestPath, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': decodeURIComponent(token),
      },
      body: JSON.stringify(requestBody),
    })
    const payload = await response.json()
    if (!response.ok) {
      throw new Error(`POST ${requestPath} failed with HTTP ${response.status}`)
    }
    return payload.data as T
  }, { requestPath: path, requestBody: body })
}

test('real DeepSeek drives the authenticated workflow through STEP07 without Mock fallback', async ({
  page,
  context,
}) => {
  test.skip(process.env.DEEPSEEK_E2E !== 'true', 'Explicit live API opt-in is required')

  const unique = Date.now().toString(36).toUpperCase()
  const hospitalCode = `DS-${unique}`
  const projectCode = `DS-P-${unique}`

  await page.goto('/workspace')
  await login(page, '', bootstrapUsername, bootstrapPassword)
  await changeInitialPassword(page, bootstrapPassword, changedAdminPassword)
  await login(page, '', bootstrapUsername, changedAdminPassword)
  await expect(page.getByText(`当前用户：${bootstrapUsername}`)).toBeVisible()

  const hospital = await postJson<{ id: string }>(page, '/api/admin/hospitals', {
    code: hospitalCode,
    name: 'DeepSeek 合成匿名测试医院',
  })
  await postJson(page, '/api/hospital/users', {
    hospitalId: hospital.id,
    username: doctorUsername,
    initialPassword: initialDoctorPassword,
    roles: ['DOCTOR', 'HOSPITAL_ADMIN'],
  })

  await context.clearCookies()
  await page.reload()
  await login(page, hospitalCode, doctorUsername, initialDoctorPassword)
  await changeInitialPassword(page, initialDoctorPassword, changedDoctorPassword)
  await login(page, hospitalCode, doctorUsername, changedDoctorPassword)

  await expect(page.getByText('模型：deepseek · deepseek-v4-flash · 真 API')).toBeVisible()
  const runtime = await page.evaluate(async () => {
    const response = await fetch('/api/runtime/model', { credentials: 'include' })
    return (await response.json()).data
  })
  expect(runtime).toEqual({
    provider: 'deepseek',
    mode: 'deepseek',
    modelName: 'deepseek-v4-flash',
    externalEnabled: true,
  })
  expect(JSON.stringify(runtime)).not.toContain('apiKey')
  expect(JSON.stringify(runtime)).not.toContain('deepseek_token')

  await page.getByPlaceholder('课题编码').fill(projectCode)
  await page.getByPlaceholder('课题名称').fill('真实 DeepSeek 合成匿名工作流')
  await page.getByRole('button', { name: '创建课题' }).click()
  await expect(page.getByRole('cell', { name: projectCode })).toBeVisible()
  await page.getByRole('row', { name: new RegExp(projectCode) })
    .getByRole('button', { name: '成员/文件' })
    .click()

  await page.getByPlaceholder('输入匿名研究想法；不要填写患者姓名、住院号或其他可识别信息').fill(
    'SYNTHETIC_ANONYMOUS：拟使用完全虚构的匿名医院历史数据库，'
    + '研究2型糖尿病成年患者使用SGLT2抑制剂与12个月eGFR变化的关联。',
  )
  await page.getByRole('button', { name: '启动后台任务' }).click()

  await expect(page.getByText('信息不完整，回答以下问题后才会生成研究方向')).toBeVisible()
  await expect(page.getByText(/WAITING_CONFIRMATION · STEP_03_ASK_CLARIFICATION/)).toBeVisible()
  const clarificationInputs = page.locator('.clarification-form textarea')
  const clarificationCount = await clarificationInputs.count()
  expect(clarificationCount).toBeGreaterThan(0)
  for (let index = 0; index < clarificationCount; index += 1) {
    await clarificationInputs.nth(index).fill(
      '合成匿名答案：虚构成年研究人群，观察期12个月，主要终点为eGFR绝对变化；'
      + '具体对照、混杂因素和统计参数需要医生及统计专家确认。',
    )
  }
  await page.getByRole('button', { name: '提交澄清信息并生成方向' }).click()

  await expect(page.getByText('请选择研究方向后继续，后台任务不会因关闭浏览器而停止')).toBeVisible()
  await expect(page.getByText(/WAITING_CONFIRMATION · STEP_05_CONFIRM_DIRECTION/)).toBeVisible()
  await expect(page.getByRole('button', { name: '确认此方向' })).toHaveCount(3)
  await page.getByRole('button', { name: '确认此方向' }).first().click()

  await expect(page.getByText(/WAITING_CONFIRMATION · STEP_07_BUILD_SEARCH_STRATEGY/)).toBeVisible()
  await expect(page.getByText('pubmed-query/v1')).toBeVisible()
  await expect(page.getByText('请复核并确认 PubMed 检索策略；确认前不会执行真实文献检索')).toBeVisible()
})
