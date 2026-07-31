import type { ProjectEvent } from '../../types/workspace'

const IDEA_PREFIX = 'medical.workspace.v2.idea.'
const CLARIFICATION_PREFIX = 'medical.workspace.v2.clarifications.'

export function shouldRefreshForEvent(
  currentVersion: number,
  event: ProjectEvent,
): boolean {
  return event.type === 'PROJECT_RESYNC_REQUIRED'
    || event.readModelVersion > currentVersion
}

export function readIdeaDraft(
  projectKey: string,
  storage: Pick<Storage, 'getItem'> = localStorage,
): string {
  try {
    return storage.getItem(IDEA_PREFIX + projectKey) ?? ''
  } catch {
    return ''
  }
}

export function writeIdeaDraft(
  projectKey: string,
  value: string,
  storage: Pick<Storage, 'setItem' | 'removeItem'> = localStorage,
) {
  try {
    if (value.trim()) storage.setItem(IDEA_PREFIX + projectKey, value)
    else storage.removeItem(IDEA_PREFIX + projectKey)
  } catch {
    // Draft persistence is best-effort; it must not block the workflow.
  }
}

export function clearIdeaDraft(
  projectKey: string,
  storage: Pick<Storage, 'removeItem'> = localStorage,
) {
  try {
    storage.removeItem(IDEA_PREFIX + projectKey)
  } catch {
    // The submitted workflow remains authoritative if browser storage is denied.
  }
}

export function readClarificationDraft(
  projectKey: string,
  storage: Pick<Storage, 'getItem'> = localStorage,
): Record<string, string> {
  let raw: string | null
  try {
    raw = storage.getItem(CLARIFICATION_PREFIX + projectKey)
  } catch {
    return {}
  }
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw) as unknown
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    return Object.fromEntries(
      Object.entries(parsed).filter(
        ([question, answer]) =>
          question.length <= 1000
          && typeof answer === 'string'
          && answer.length <= 1000,
      ),
    )
  } catch {
    return {}
  }
}

export function writeClarificationDraft(
  projectKey: string,
  value: Record<string, string>,
  storage: Pick<Storage, 'setItem' | 'removeItem'> = localStorage,
) {
  const meaningful = Object.fromEntries(
    Object.entries(value).filter(([, answer]) => answer.trim()),
  )
  try {
    if (Object.keys(meaningful).length) {
      storage.setItem(
        CLARIFICATION_PREFIX + projectKey,
        JSON.stringify(meaningful),
      )
    } else {
      storage.removeItem(CLARIFICATION_PREFIX + projectKey)
    }
  } catch {
    // Draft persistence is best-effort; it must not block the workflow.
  }
}

export function clearClarificationDraft(
  projectKey: string,
  storage: Pick<Storage, 'removeItem'> = localStorage,
) {
  try {
    storage.removeItem(CLARIFICATION_PREFIX + projectKey)
  } catch {
    // The submitted workflow remains authoritative if browser storage is denied.
  }
}
