import { createRouter, createWebHistory } from 'vue-router'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'prototype', component: () => import('../views/PrototypeView.vue') },
    { path: '/workspace', name: 'workspace', component: () => import('../views/WorkspaceView.vue') },
  ],
})
