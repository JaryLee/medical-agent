import type { Directive } from 'vue'
import type { Pinia } from 'pinia'
import { useSessionStore } from '../stores/session'

function applyPermission(element: HTMLElement, roles: string[], pinia: Pinia) {
  const userRoles = useSessionStore(pinia).user?.roles ?? []
  element.hidden = !roles.some((role) => userRoles.includes(role))
}

export function permissionDirective(pinia: Pinia): Directive<HTMLElement, string[]> {
  return {
    mounted(element, binding) {
      applyPermission(element, binding.value, pinia)
    },
    updated(element, binding) {
      applyPermission(element, binding.value, pinia)
    },
  }
}
