export type ArtifactRecord = Record<string, unknown>

export function asRecord(value: unknown): ArtifactRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as ArtifactRecord
    : {}
}

export function asRecords(value: unknown): ArtifactRecord[] {
  return Array.isArray(value) ? value.map(asRecord) : []
}

export function asStrings(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : []
}

export function text(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback
}

export function count(value: unknown): number {
  return typeof value === 'number' ? value : 0
}

export function flag(value: unknown): boolean {
  return value === true
}
