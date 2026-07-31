import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { createAppRouter } from '../router'
import { createFeatureFlags, parseBooleanFlag } from './featureFlags'

describe('workspace feature flags', () => {
  it('uses safe migration defaults', () => {
    expect(createFeatureFlags({})).toEqual({
      workspaceV2Enabled: false,
      legacyWorkspaceEnabled: true,
    })
  })

  it('accepts explicit boolean forms and falls back for invalid values', () => {
    expect(parseBooleanFlag('ON', false)).toBe(true)
    expect(parseBooleanFlag('0', true)).toBe(false)
    expect(parseBooleanFlag('unexpected', false)).toBe(false)
  })

  it('rejects configuration that disables both workspaces', () => {
    expect(() =>
      createFeatureFlags({
        VITE_WORKSPACE_V2_ENABLED: 'false',
        VITE_LEGACY_WORKSPACE_ENABLED: 'false',
      }),
    ).toThrow('At least one workspace implementation must be enabled.')
  })

  it('keeps the legacy workspace as default and fixed fallback', () => {
    const router = createAppRouter(
      { workspaceV2Enabled: false, legacyWorkspaceEnabled: true },
      createMemoryHistory(),
    )

    expect(router.resolve('/workspace').meta.workspaceGeneration).toBe('legacy')
    expect(router.resolve('/workspace/legacy').meta).toMatchObject({
      workspaceGeneration: 'legacy',
      fallback: true,
    })
  })

  it('switches only the primary route when V2 is explicitly enabled', () => {
    const router = createAppRouter(
      { workspaceV2Enabled: true, legacyWorkspaceEnabled: true },
      createMemoryHistory(),
    )

    expect(router.resolve('/workspace').meta.workspaceGeneration).toBe('v2')
    expect(router.resolve('/workspace/legacy').meta.workspaceGeneration).toBe('legacy')
    expect(router.resolve('/todos').meta.workspaceGeneration).toBe('v2')
    expect(router.resolve('/model-evaluations').meta.workspaceGeneration).toBe('v2')
    expect(
      router.resolve('/projects/prj_0123456789ABCDEFGHJKMNPQRS/idea')
        .meta.workspaceGeneration,
    ).toBe('v2')
  })

  it('does not expose V2 deep links while the feature is disabled', async () => {
    const router = createAppRouter(
      { workspaceV2Enabled: false, legacyWorkspaceEnabled: true },
      createMemoryHistory(),
    )
    await router.push('/projects/prj_0123456789ABCDEFGHJKMNPQRS/idea')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('workspace-legacy')
    await router.push('/model-evaluations')
    expect(router.currentRoute.value.name).toBe('workspace-legacy')
  })
})
