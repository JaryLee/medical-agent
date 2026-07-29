import { defineStore } from 'pinia'
import {
  currentUser,
  login,
  type CurrentUser,
} from '../api/platform'

export const useSessionStore = defineStore('session', {
  state: () => ({
    user: undefined as CurrentUser | undefined,
    restored: false,
  }),
  actions: {
    async signIn(hospitalCode: string, username: string, password: string) {
      this.user = await login(hospitalCode, username, password)
      this.restored = true
      return this.user
    },
    async restore() {
      if (this.restored) return this.user
      try {
        this.user = await currentUser()
      } catch {
        this.user = undefined
      } finally {
        this.restored = true
      }
      return this.user
    },
    clear() {
      this.user = undefined
      this.restored = true
    },
  },
})
