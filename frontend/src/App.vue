<template>
  <main class="shell">
    <nav class="app-nav">
      <router-link to="/">
        匿名研究原型
      </router-link>
      <router-link to="/workspace">
        工程工作台
      </router-link>
    </nav>
    <router-view />
  </main>
</template>
