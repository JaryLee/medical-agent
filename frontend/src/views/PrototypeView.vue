<script setup lang="ts">
import { ref } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { analyzeIdea, confirmDirection } from '../api/prototype'
import type { AnalysisResult, PrototypeResult } from '../types/research'

const idea = ref('我想研究2型糖尿病患者使用SGLT2抑制剂后肾功能的变化')
const loading = ref(false)
const analysis = ref<AnalysisResult>()
const result = ref<PrototypeResult>()
const selectedDirection = ref('')
const authRequired = ref(false)

async function analyze() {
  loading.value = true
  result.value = undefined
  authRequired.value = false
  try {
    analysis.value = await analyzeIdea(idea.value)
    selectedDirection.value = analysis.value.directions[1]?.id ?? ''
  } catch (error) {
    if (axios.isAxiosError(error) && [401, 403].includes(error.response?.status ?? 0)) {
      authRequired.value = true
      ElMessage.warning('解析接口需要登录，请先进入工程工作台登录')
    } else {
      ElMessage.error('解析失败，请检查后端日志和网络连接')
    }
  } finally {
    loading.value = false
  }
}

async function confirm() {
  if (!selectedDirection.value) return
  loading.value = true
  try {
    result.value = await confirmDirection(idea.value, selectedDirection.value)
  } catch {
    ElMessage.error('生成失败，系统不会编造降级结果')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <header>
    <span class="eyebrow">STAGE 0 · ANONYMOUS PROTOTYPE</span>
    <h1>把一句临床观察，整理成可讨论的研究方向</h1>
    <p>模型 Provider 由后端安全配置决定；当前状态请以登录后工作台标识为准，禁止输入真实患者明细。</p>
  </header>

  <el-card class="panel">
    <template #header>
      <strong>匿名研究想法</strong>
    </template>
    <el-input
      v-model="idea"
      type="textarea"
      :rows="4"
      maxlength="2000"
      show-word-limit
    />
    <el-button
      type="primary"
      :loading="loading"
      @click="analyze"
    >
      提取研究要素
    </el-button>
    <el-alert
      v-if="authRequired"
      title="该原型接口受登录和 CSRF 保护"
      type="warning"
      :closable="false"
      class="auth-hint"
    >
      <a href="/workspace">
        进入工程工作台登录
      </a>
      <span>，登录后返回本页即可继续提取。</span>
    </el-alert>
  </el-card>

  <section
    v-if="analysis"
    class="grid"
  >
    <el-card>
      <template #header>
        <strong>需要澄清</strong>
      </template>
      <ol>
        <li
          v-for="question in analysis.clarificationQuestions"
          :key="question"
        >
          {{ question }}
        </li>
      </ol>
    </el-card>
    <el-card>
      <template #header>
        <strong>三个观察性研究方向</strong>
      </template>
      <el-radio-group
        v-model="selectedDirection"
        class="directions"
      >
        <el-radio
          v-for="direction in analysis.directions"
          :key="direction.id"
          :value="direction.id"
        >
          <span>{{ direction.title }}</span>
          <small>{{ direction.researchPurpose }}</small>
        </el-radio>
      </el-radio-group>
      <el-button
        type="primary"
        :loading="loading"
        @click="confirm"
      >
        确认并生成 PECO
      </el-button>
    </el-card>
  </section>

  <el-card
    v-if="result"
    class="result"
  >
    <template #header>
      <strong>PECO 与可追溯证据</strong>
    </template>
    <dl>
      <dt>P</dt><dd>{{ result.peco.population }}</dd>
      <dt>E</dt><dd>{{ result.peco.exposure }}</dd>
      <dt>C</dt><dd>{{ result.peco.comparator }}</dd>
      <dt>O</dt><dd>{{ result.peco.outcome }}</dd>
    </dl>
    <h2>研究问题</h2>
    <p>{{ result.peco.researchQuestion }}</p>
    <h2>背景草案</h2>
    <p>{{ result.background }}</p>
    <h2>Mock PubMed 快照</h2>
    <ul>
      <li
        v-for="item in result.literature"
        :key="item.pmid"
      >
        [{{ item.citationId }}] {{ item.title }} · PMID {{ item.pmid }} · {{ item.evidenceScope }}
      </li>
    </ul>
    <el-alert
      :title="result.evidenceDisclaimer"
      type="warning"
      :closable="false"
    />
  </el-card>
</template>
