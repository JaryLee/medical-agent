import { describe, expect, it } from 'vitest'
import { createIdempotencyKey } from '../../api/workspaceV2'
import type { ProjectEvent } from '../../types/workspace'
import {
  clearClarificationDraft,
  clearIdeaDraft,
  readClarificationDraft,
  readIdeaDraft,
  shouldRefreshForEvent,
  writeClarificationDraft,
  writeIdeaDraft,
} from './workspaceState'

function memoryStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  }
}

function event(
  type: ProjectEvent['type'],
  readModelVersion: number,
): ProjectEvent {
  return {
    eventId: 10,
    type,
    projectKey: 'prj_0123456789ABCDEFGHJKMNPQRS',
    readModelVersion,
    occurredAt: '2026-07-30T08:00:00Z',
  }
}

describe('workspace V2 state rules', () => {
  it('rejects stale notifications and always honors resync signals', () => {
    expect(
      shouldRefreshForEvent(
        7,
        event('PROJECT_READ_MODEL_CHANGED', 7),
      ),
    ).toBe(false)
    expect(
      shouldRefreshForEvent(
        7,
        event('PROJECT_READ_MODEL_CHANGED', 8),
      ),
    ).toBe(true)
    expect(
      shouldRefreshForEvent(
        7,
        event('PROJECT_RESYNC_REQUIRED', 7),
      ),
    ).toBe(true)
  })

  it('restores and clears only idea and clarification text drafts', () => {
    const storage = memoryStorage()
    const projectKey = 'prj_0123456789ABCDEFGHJKMNPQRS'

    writeIdeaDraft(projectKey, '匿名研究构想', storage)
    expect(readIdeaDraft(projectKey, storage)).toBe('匿名研究构想')

    writeClarificationDraft(
      projectKey,
      { '研究对象来自哪里？': '本院匿名病例', '空答案': '  ' },
      storage,
    )
    expect(readClarificationDraft(projectKey, storage)).toEqual({
      '研究对象来自哪里？': '本院匿名病例',
    })

    clearIdeaDraft(projectKey, storage)
    clearClarificationDraft(projectKey, storage)
    expect(readIdeaDraft(projectKey, storage)).toBe('')
    expect(readClarificationDraft(projectKey, storage)).toEqual({})
  })

  it('creates bounded non-control idempotency keys', () => {
    const key = createIdempotencyKey('START_RESEARCH_IDEA')
    expect(key.length).toBeGreaterThanOrEqual(16)
    expect(key.length).toBeLessThanOrEqual(128)
    expect(
      Array.from(key).some((character) => {
        const code = character.charCodeAt(0)
        return code < 32 || code === 127
      }),
    ).toBe(false)
  })

  it('keeps the workflow usable when browser storage is unavailable', () => {
    const denied = {
      getItem: () => {
        throw new Error('storage denied')
      },
      setItem: () => {
        throw new Error('storage denied')
      },
      removeItem: () => {
        throw new Error('storage denied')
      },
    }
    const projectKey = 'prj_0123456789ABCDEFGHJKMNPQRS'

    expect(readIdeaDraft(projectKey, denied)).toBe('')
    expect(readClarificationDraft(projectKey, denied)).toEqual({})
    expect(() => writeIdeaDraft(projectKey, '匿名构想', denied)).not.toThrow()
    expect(() =>
      writeClarificationDraft(projectKey, { 问题: '匿名答案' }, denied),
    ).not.toThrow()
  })
})
