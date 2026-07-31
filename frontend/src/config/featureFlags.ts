export interface FeatureFlags {
  workspaceV2Enabled: boolean
  legacyWorkspaceEnabled: boolean
}

export function parseBooleanFlag(value: string | undefined, defaultValue: boolean): boolean {
  if (value === undefined || value.trim() === '') {
    return defaultValue
  }

  const normalized = value.trim().toLowerCase()
  if (['true', '1', 'yes', 'on'].includes(normalized)) {
    return true
  }
  if (['false', '0', 'no', 'off'].includes(normalized)) {
    return false
  }

  return defaultValue
}

export function createFeatureFlags(env: Record<string, string | boolean | undefined>): FeatureFlags {
  const flags = {
    workspaceV2Enabled: parseBooleanFlag(
      typeof env.VITE_WORKSPACE_V2_ENABLED === 'string'
        ? env.VITE_WORKSPACE_V2_ENABLED
        : undefined,
      false,
    ),
    legacyWorkspaceEnabled: parseBooleanFlag(
      typeof env.VITE_LEGACY_WORKSPACE_ENABLED === 'string'
        ? env.VITE_LEGACY_WORKSPACE_ENABLED
        : undefined,
      true,
    ),
  }

  if (!flags.workspaceV2Enabled && !flags.legacyWorkspaceEnabled) {
    throw new Error('At least one workspace implementation must be enabled.')
  }

  return Object.freeze(flags)
}

export const featureFlags = createFeatureFlags(import.meta.env)

