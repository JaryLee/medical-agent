import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import PrototypeView from './views/PrototypeView.vue'

vi.mock('./api/prototype', () => ({
  analyzeIdea: vi.fn().mockResolvedValue({
    clarificationQuestions: ['研究对象来自哪里？'],
    directions: [
      { id: 'DIR-01', title: '横断面', recommendedStudyType: 'CROSS_SECTIONAL', researchPurpose: '描述', limitations: [] },
      { id: 'DIR-02', title: '队列', recommendedStudyType: 'COHORT', researchPurpose: '关联', limitations: [] },
      { id: 'DIR-03', title: '病例对照', recommendedStudyType: 'CASE_CONTROL', researchPurpose: '探索', limitations: [] },
    ],
    disclaimer: '建议',
  }),
  confirmDirection: vi.fn(),
}))

describe('PrototypeView', () => {
  it('shows three observational directions after analysis', async () => {
    const wrapper = mount(PrototypeView, { global: { plugins: [ElementPlus] } })
    const analyzeButton = wrapper.findAll('button').find((button) => button.text().includes('提取研究要素'))
    expect(analyzeButton).toBeDefined()
    await analyzeButton!.trigger('click')
    await new Promise((resolve) => setTimeout(resolve))
    expect(wrapper.text()).toContain('横断面')
    expect(wrapper.text()).toContain('队列')
    expect(wrapper.text()).toContain('病例对照')
  })
})
