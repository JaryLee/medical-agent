<script setup lang="ts">
import { reactive, ref } from 'vue'

const emit = defineEmits<{
  submit: [credentials: {
    hospitalCode: string
    username: string
    password: string
  }]
}>()

defineProps<{
  busy: boolean
  errorMessage?: string
}>()

const credentials = reactive({
  hospitalCode: '',
  username: '',
  password: '',
})
const formError = ref('')

function submit() {
  formError.value = ''
  if (
    !credentials.hospitalCode.trim()
    || !credentials.username.trim()
    || !credentials.password
  ) {
    formError.value = '请填写医院编码、用户名和密码。'
    return
  }
  emit('submit', {
    hospitalCode: credentials.hospitalCode.trim(),
    username: credentials.username.trim(),
    password: credentials.password,
  })
}
</script>

<template>
  <section
    class="v2-login-card"
    aria-labelledby="workspace-login-title"
  >
    <div>
      <span class="eyebrow">匿名医疗科研协作</span>
      <h1 id="workspace-login-title">
        登录课题工作台
      </h1>
      <p>使用医院分配的账号访问您参与的科研课题。</p>
    </div>
    <el-form
      label-position="top"
      class="v2-login-form"
      @submit.prevent="submit"
    >
      <el-form-item label="医院编码">
        <el-input
          v-model="credentials.hospitalCode"
          autocomplete="organization"
          :disabled="busy"
        />
      </el-form-item>
      <el-form-item label="用户名">
        <el-input
          v-model="credentials.username"
          autocomplete="username"
          :disabled="busy"
        />
      </el-form-item>
      <el-form-item label="密码">
        <el-input
          v-model="credentials.password"
          type="password"
          show-password
          autocomplete="current-password"
          :disabled="busy"
          @keyup.enter="submit"
        />
      </el-form-item>
      <p
        v-if="formError || errorMessage"
        class="v2-form-error"
        role="alert"
      >
        {{ formError || errorMessage }}
      </p>
      <el-button
        type="primary"
        native-type="submit"
        :loading="busy"
      >
        登录
      </el-button>
    </el-form>
  </section>
</template>
