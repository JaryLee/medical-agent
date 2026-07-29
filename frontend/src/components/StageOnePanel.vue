<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import {
  addExpertReviewComment,
  changePassword,
  cancelAgentTask,
  confirmExpertReviewByOwner,
  confirmAgentDirection,
  confirmAgentSearchStrategy,
  confirmAgentObservationalDesign,
  createCitationStyle,
  createProject,
  createAgentTask,
  confirmDocumentExport,
  documentExportDownloadUrl,
  getAgentTask,
  getDocumentExport,
  getExpertReview,
  installDefaultCitationStyle,
  installDefaultDocumentTemplate,
  addProjectMember,
  listAudits,
  listAgentTasks,
  listAgentClarifications,
  listCitationStyles,
  listDocumentTemplates,
  listProjectMembers,
  listProjects,
  publishDocumentTemplate,
  previewDocumentTemplate,
  publishCitationStyle,
  retryAgentTask,
  submitExpertReviewDecision,
  submitAgentClarifications,
  uploadDocumentTemplate,
  uploadProjectFile,
  type AuditEntry,
  type AgentTask,
  type AgentClarificationRound,
  type CitationStyle,
  type DocumentExport,
  type DocumentTemplate,
  type ExpertReview,
  type ExpertReviewCommentType,
  type Project,
  type ProjectMember,
  type ProjectMemberRole,
  type UploadedProjectFile,
} from '../api/platform'
import { useSessionStore } from '../stores/session'

const hospitalCode = ref('')
const username = ref('')
const password = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const session = useSessionStore()
const { user } = storeToRefs(session)
const projects = ref<Project[]>([])
const projectCode = ref('')
const projectName = ref('')
const loading = ref(false)
const activeProject = ref<Project>()
const members = ref<ProjectMember[]>([])
const memberUserId = ref('')
const memberRole = ref<ProjectMemberRole>('VIEWER')
const selectedFile = ref<File>()
const uploadedFile = ref<UploadedProjectFile>()
const audits = ref<AuditEntry[]>([])
const researchIdea = ref('')
const agentTask = ref<AgentTask>()
const clarificationAnswers = ref<Record<string, string>>({})
const clarificationHistory = ref<AgentClarificationRound[]>([])
const searchQuery = ref('')
const designStudyType = ref<'CROSS_SECTIONAL' | 'COHORT' | 'CASE_CONTROL'>('COHORT')
const primaryOutcome = ref('')
const protocolGenerationAuthorized = ref(false)
const expertReview = ref<ExpertReview>()
const reviewTargetType = ref<'SECTION' | 'STROBE'>('SECTION')
const reviewSectionId = ref('')
const reviewStrobeItemId = ref('')
const reviewCommentType = ref<ExpertReviewCommentType>('GENERAL')
const reviewComment = ref('')
const reviewDecisionSummary = ref('')
const documentTemplates = ref<DocumentTemplate[]>([])
const selectedTemplateId = ref('')
const citationStyles = ref<CitationStyle[]>([])
const selectedCitationStyleId = ref('')
const citationStyleCode = ref('INSTITUTION_NUMERIC')
const citationStyleName = ref('')
const citationLayout = ref<'VANCOUVER' | 'GB_T_7714'>('VANCOUVER')
const citationAuthorLimit = ref(6)
const citationEtAlText = ref('等')
const citationIncludeDoi = ref(true)
const citationIncludeEvidenceScope = ref(true)
const citationEvidenceScopeLabel = ref('摘要级证据')
const confirmReviewedContent = ref(false)
const documentExport = ref<DocumentExport>()
const documentTemplateCode = ref('OBSERVATIONAL_PROTOCOL')
const documentTemplateName = ref('')
const documentTemplateFile = ref<File>()
const isExpert = computed(() => user.value?.roles.includes('EXPERT') ?? false)
const isHospitalAdmin = computed(
  () => user.value?.roles.includes('HOSPITAL_ADMIN') ?? false,
)
const isProjectOwner = computed(() => members.value.some(
  (member) => member.userId === user.value?.userId && member.role === 'OWNER',
))
let agentEvents: ReturnType<typeof openEventStream> | undefined
onMounted(async () => {
  const restoredUser = await session.restore()
  if (restoredUser && !restoredUser.forcePasswordChange) {
    projects.value = await listProjects()
    await Promise.all([refreshDocumentTemplates(), refreshCitationStyles()])
  }
})

async function signIn() {
  loading.value = true
  try {
    user.value = await session.signIn(hospitalCode.value, username.value, password.value)
    if (!user.value.forcePasswordChange) {
      projects.value = await listProjects()
      await Promise.all([refreshDocumentTemplates(), refreshCitationStyles()])
    }
  } catch {
    ElMessage.error('登录失败或账号已锁定')
  } finally {
    loading.value = false
  }
}

async function updatePassword() {
  try {
    await changePassword(currentPassword.value, newPassword.value)
    ElMessage.success('密码已修改，请重新登录')
    session.clear()
    password.value = ''
  } catch {
    ElMessage.error('密码修改失败')
  }
}

async function addProject() {
  try {
    await createProject(projectCode.value, projectName.value)
    projects.value = await listProjects()
    projectCode.value = ''
    projectName.value = ''
  } catch {
    ElMessage.error('课题创建失败')
  }
}

async function manageProject(project: Project) {
  agentEvents?.close()
  activeProject.value = project
  uploadedFile.value = undefined
  agentTask.value = undefined
  clarificationHistory.value = []
  documentExport.value = undefined
  confirmReviewedContent.value = false
  try {
    members.value = await listProjectMembers(project.id)
    const tasks = await listAgentTasks(project.id)
    const latestTask = tasks[0]
    setAgentTask(latestTask)
    if (latestTask) {
      researchIdea.value = latestTask.input.idea
      if (!['COMPLETED', 'FAILED', 'CANCELLED'].includes(latestTask.status)) {
        connectAgentEvents(latestTask.id)
      }
    }
  } catch {
    ElMessage.error('无法读取课题成员')
  }
}

async function startAgent() {
  if (!activeProject.value || !researchIdea.value.trim()) return
  try {
    const createdTask = await createAgentTask(activeProject.value.id, researchIdea.value)
    setAgentTask(createdTask)
    connectAgentEvents(createdTask.id)
    ElMessage.success('Agent任务已进入后台队列')
  } catch {
    ElMessage.error('Agent任务创建失败，请确认当前账号有课题编辑权限')
  }
}

function connectAgentEvents(taskId: string) {
  agentEvents?.close()
  agentEvents = openEventStream(taskId)
  const refresh = async () => {
    const refreshedTask = await getAgentTask(taskId)
    setAgentTask(refreshedTask)
    if (['COMPLETED', 'FAILED', 'CANCELLED', 'REVISION_REQUIRED']
      .includes(refreshedTask.status)) {
      agentEvents?.close()
    }
  }
  const eventTypes = [
    'TASK_STARTED',
    'STEP_COMPLETED',
    'WAITING_CLARIFICATION',
    'CLARIFICATIONS_CONFIRMED',
    'WAITING_CONFIRMATION',
    'DIRECTION_CONFIRMED',
    'WAITING_SEARCH_STRATEGY',
    'SEARCH_STRATEGY_CONFIRMED',
    'LITERATURE_SEARCH_COMPLETED',
    'CLINICAL_TRIALS_SEARCH_COMPLETED',
    'LITERATURE_VALIDATION_COMPLETED',
    'SIMILAR_RESEARCH_ANALYSIS_COMPLETED',
    'WAITING_OBSERVATIONAL_DESIGN_CONFIRMATION',
    'OBSERVATIONAL_DESIGN_CONFIRMED',
    'PROTOCOL_GENERATION_QUEUED',
    'PROTOCOL_SECTIONS_GENERATED',
    'STATISTICAL_DRAFT_GENERATED',
    'CLAIMS_AND_CITATIONS_VALIDATED',
    'STROBE_COMPLETENESS_CHECKED',
    'EXPERT_REVIEW_REQUIRED',
    'EXPERT_REVIEW_COMMENT_ADDED',
    'EXPERT_APPROVED',
    'RETURNED_FOR_REVISION',
    'EXPERT_REVIEW_COMPLETED',
    'EXPORT_CONFIRMATION_REQUIRED',
    'DOCUMENT_EXPORT_COMPLETED',
    'TASK_COMPLETED',
    'TASK_FAILED',
    'TASK_CANCELLED',
  ]
  eventTypes.forEach((eventType) => agentEvents?.addEventListener(eventType, refresh))
  agentEvents.onerror = () => {
    void refresh()
  }
}

function setAgentTask(task?: AgentTask) {
  agentTask.value = task
  if (task?.status === 'WAITING_CONFIRMATION'
    && ['STEP_03_ASK_CLARIFICATION', 'STEP_05_CONFIRM_DIRECTION'].includes(task.currentStep)) {
    const answers = { ...(task.input.clarificationAnswers ?? {}) }
    task.output?.clarificationQuestions?.forEach((question) => {
      if (!(question in answers)) answers[question] = ''
    })
    clarificationAnswers.value = answers
  }
  if (task?.output?.searchStrategy) {
    searchQuery.value = task.output.searchStrategy.pubmedQuery
  }
  const recommendation = task?.output?.observationalDesignRecommendation
  if (recommendation?.confirmationStatus === 'PENDING_CONFIRMATION') {
    designStudyType.value = recommendation.recommendedStudyType
    primaryOutcome.value = recommendation.primaryOutcomeCandidate
    protocolGenerationAuthorized.value = false
  }
  if (task) void refreshClarificationHistory(task.id)
  if (task && (task.output?.expertReview
    || ['STEP_17_WAIT_EXPERT_REVIEW', 'STEP_18_EXPORT_DOCUMENT'].includes(
      task.currentStep,
    ))) {
    void refreshExpertReview(task.id)
  } else {
    expertReview.value = undefined
  }
  if (task && (task.currentStep === 'STEP_18_EXPORT_DOCUMENT'
    || task.output?.documentExport)) {
    void refreshDocumentExport(task.id)
  } else {
    documentExport.value = undefined
  }
}

async function refreshClarificationHistory(taskId: string) {
  try {
    clarificationHistory.value = await listAgentClarifications(taskId)
  } catch {
    clarificationHistory.value = []
  }
}

async function refreshExpertReview(taskId: string) {
  try {
    expertReview.value = await getExpertReview(taskId)
    if (!reviewSectionId.value) {
      reviewSectionId.value = agentTask.value?.output?.protocolDraft?.sections[0]?.sectionId ?? ''
    }
    if (!reviewStrobeItemId.value) {
      reviewStrobeItemId.value =
        agentTask.value?.output?.strobeCompletenessCheck?.items[0]?.itemResultId ?? ''
    }
  } catch {
    expertReview.value = undefined
  }
}

async function refreshDocumentTemplates() {
  try {
    documentTemplates.value = await listDocumentTemplates()
    const published = documentTemplates.value.find((value) => value.status === 'PUBLISHED')
    if (published) selectedTemplateId.value = published.id
  } catch {
    documentTemplates.value = []
  }
}

async function refreshCitationStyles() {
  try {
    citationStyles.value = await listCitationStyles()
    const published = citationStyles.value.find((value) => value.status === 'PUBLISHED')
    if (published) selectedCitationStyleId.value = published.id
  } catch {
    citationStyles.value = []
  }
}

async function refreshDocumentExport(taskId: string) {
  try {
    documentExport.value = await getDocumentExport(taskId)
  } catch {
    documentExport.value = undefined
  }
}

function formatTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function openEventStream(taskId: string) {
  return new globalThis.EventSource(`/api/agent/tasks/${taskId}/events`)
}

async function confirmDirection(directionId: string) {
  if (!agentTask.value) return
  agentTask.value = await confirmAgentDirection(agentTask.value.id, directionId)
  connectAgentEvents(agentTask.value.id)
}

async function submitClarifications() {
  if (!agentTask.value) return
  try {
    setAgentTask(await submitAgentClarifications(
      agentTask.value.id,
      clarificationAnswers.value,
    ))
    await refreshClarificationHistory(agentTask.value!.id)
    connectAgentEvents(agentTask.value!.id)
  } catch {
    ElMessage.error('请逐项填写全部澄清问题')
  }
}

async function confirmSearchStrategy() {
  if (!agentTask.value || !searchQuery.value.trim()) return
  try {
    setAgentTask(await confirmAgentSearchStrategy(
      agentTask.value.id,
      searchQuery.value,
    ))
    connectAgentEvents(agentTask.value!.id)
    ElMessage.success('检索策略已确认，PubMed 与 ClinicalTrials.gov 检索已进入后台队列')
  } catch {
    ElMessage.error('检索策略确认失败，请检查检索式或课题编辑权限')
  }
}

async function confirmObservationalDesign() {
  if (!agentTask.value || !primaryOutcome.value.trim()
    || !protocolGenerationAuthorized.value) return
  try {
    setAgentTask(await confirmAgentObservationalDesign(
      agentTask.value.id,
      designStudyType.value,
      primaryOutcome.value,
      protocolGenerationAuthorized.value,
    ))
    connectAgentEvents(agentTask.value!.id)
    ElMessage.success('设计已确认，正式研究方案章节已进入后台生成队列')
  } catch {
    ElMessage.error('设计确认失败，请检查必填信息、课题编辑权限和当前任务状态')
  }
}

async function cancelTask() {
  if (!agentTask.value) return
  agentTask.value = await cancelAgentTask(agentTask.value.id)
  agentEvents?.close()
}

async function retryTask() {
  if (!agentTask.value) return
  agentTask.value = await retryAgentTask(agentTask.value.id)
  connectAgentEvents(agentTask.value.id)
}

async function addReviewComment() {
  if (!agentTask.value || !reviewComment.value.trim()) return
  const section = agentTask.value.output?.protocolDraft?.sections.find(
    (value) => value.sectionId === reviewSectionId.value,
  )
  try {
    expertReview.value = await addExpertReviewComment(agentTask.value.id, {
      protocolSectionId: reviewTargetType.value === 'SECTION' ? section?.sectionId : undefined,
      protocolSectionVersionNo:
        reviewTargetType.value === 'SECTION' ? section?.versionNo : undefined,
      strobeItemResultId:
        reviewTargetType.value === 'STROBE' ? reviewStrobeItemId.value : undefined,
      commentType: reviewCommentType.value,
      content: reviewComment.value,
    })
    reviewComment.value = ''
    ElMessage.success('专家批注已保存并写入审核历史')
  } catch {
    ElMessage.error('批注保存失败，请检查专家角色、课题权限和批注目标')
  }
}

async function decideReview(decision: 'APPROVE' | 'RETURN_FOR_REVISION') {
  if (!agentTask.value || !expertReview.value || !reviewDecisionSummary.value.trim()) return
  try {
    expertReview.value = await submitExpertReviewDecision(
      agentTask.value.id,
      decision,
      reviewDecisionSummary.value,
      expertReview.value.version,
    )
    setAgentTask(await getAgentTask(agentTask.value.id))
    ElMessage.success(
      decision === 'APPROVE' ? '专家审核已通过，等待课题负责人确认' : '方案已退回修改',
    )
  } catch {
    ElMessage.error('审核决定提交失败；退回修改前必须至少添加一条定位批注')
  }
}

async function ownerConfirmReview() {
  if (!agentTask.value || !expertReview.value) return
  try {
    expertReview.value = await confirmExpertReviewByOwner(
      agentTask.value.id,
      expertReview.value.version,
    )
    setAgentTask(await getAgentTask(agentTask.value.id))
    ElMessage.success('审核版本已确认并锁定，任务进入 STEP18 导出确认')
  } catch {
    ElMessage.error('确认失败，仅课题负责人可确认已由专家通过的审核版本')
  }
}

async function installDefaultTemplate() {
  try {
    const created = await installDefaultDocumentTemplate()
    await refreshDocumentTemplates()
    selectedTemplateId.value = created.id
    ElMessage.success('内置受控 DOCX 模板已校验，发布后即可正式导出')
  } catch {
    ElMessage.error('模板安装失败，请确认医院管理员权限和对象存储状态')
  }
}

function chooseDocumentTemplate(event: Event) {
  documentTemplateFile.value = (event.target as HTMLInputElement).files?.[0]
}

async function uploadTemplate() {
  if (!documentTemplateCode.value.trim()
    || !documentTemplateName.value.trim()
    || !documentTemplateFile.value) return
  try {
    const created = await uploadDocumentTemplate(
      documentTemplateCode.value,
      documentTemplateName.value,
      documentTemplateFile.value,
    )
    await refreshDocumentTemplates()
    selectedTemplateId.value = created.id
    documentTemplateName.value = ''
    documentTemplateFile.value = undefined
    ElMessage.success('模板已完成安全、结构和占位符校验，发布后可用于正式导出')
  } catch {
    ElMessage.error('模板上传失败；请检查 DOCX 结构、宏/外链和受控占位符')
  }
}

async function publishTemplate(template: DocumentTemplate) {
  try {
    const published = await publishDocumentTemplate(template.id, template.version)
    await refreshDocumentTemplates()
    selectedTemplateId.value = published.id
    ElMessage.success(`模板 ${published.templateCode} v${published.versionNo} 已发布`)
  } catch {
    ElMessage.error('模板发布失败，请刷新版本或检查医院管理员权限')
  }
}

async function previewTemplate(template: DocumentTemplate) {
  try {
    const content = await previewDocumentTemplate(template.id)
    const url = globalThis.URL.createObjectURL(content)
    const anchor = globalThis.document.createElement('a')
    anchor.href = url
    anchor.download = `${template.templateCode}-v${template.versionNo}-试生成.docx`
    anchor.click()
    globalThis.URL.revokeObjectURL(url)
    ElMessage.success('模板试生成文档已完成，可用 LibreOffice 检查基础格式')
  } catch {
    ElMessage.error('模板试生成失败，请检查模板结构和医院管理员权限')
  }
}

async function installDefaultStyle() {
  try {
    const created = await installDefaultCitationStyle()
    await refreshCitationStyles()
    selectedCitationStyleId.value = created.id
    ElMessage.success('内置机构数字引用格式已校验，发布后即可用于导出')
  } catch {
    ElMessage.error('引用格式安装失败，请确认医院管理员权限')
  }
}

async function saveCitationStyle() {
  if (!citationStyleCode.value.trim()
    || !citationStyleName.value.trim()
    || !citationEtAlText.value.trim()
    || !citationEvidenceScopeLabel.value.trim()) return
  try {
    const created = await createCitationStyle({
      styleCode: citationStyleCode.value,
      styleName: citationStyleName.value,
      layout: citationLayout.value,
      authorLimit: citationAuthorLimit.value,
      etAlText: citationEtAlText.value,
      includeDoi: citationIncludeDoi.value,
      includeEvidenceScope: citationIncludeEvidenceScope.value,
      evidenceScopeLabel: citationEvidenceScopeLabel.value,
    })
    await refreshCitationStyles()
    selectedCitationStyleId.value = created.id
    citationStyleName.value = ''
    ElMessage.success('医院引用格式版本已创建，发布后可用于正式导出')
  } catch {
    ElMessage.error('引用格式保存失败，请检查代码、布局和字段长度')
  }
}

async function publishStyle(style: CitationStyle) {
  try {
    const published = await publishCitationStyle(style.id, style.version)
    await refreshCitationStyles()
    selectedCitationStyleId.value = published.id
    ElMessage.success(`引用格式 ${published.styleCode} v${published.versionNo} 已发布`)
  } catch {
    ElMessage.error('引用格式发布失败，请刷新版本或检查医院管理员权限')
  }
}

async function exportDocument() {
  if (!agentTask.value
    || !selectedTemplateId.value
    || !selectedCitationStyleId.value
    || !confirmReviewedContent.value) return
  try {
    documentExport.value = await confirmDocumentExport(
      agentTask.value.id,
      selectedTemplateId.value,
      selectedCitationStyleId.value,
      confirmReviewedContent.value,
    )
    setAgentTask(await getAgentTask(agentTask.value.id))
    agentEvents?.close()
    ElMessage.success('STEP18 已完成，受控 Word 文档可下载')
  } catch {
    ElMessage.error('导出失败，请检查模板发布状态、审核锁定版本和引用一致性')
  }
}

onUnmounted(() => agentEvents?.close())

async function addMember() {
  if (!activeProject.value || !memberUserId.value) return
  try {
    await addProjectMember(activeProject.value.id, memberUserId.value, memberRole.value)
    members.value = await listProjectMembers(activeProject.value.id)
    memberUserId.value = ''
    ElMessage.success('课题成员已添加')
  } catch {
    ElMessage.error('添加失败，请确认用户属于本院且当前账号有管理权限')
  }
}

function chooseFile(event: Event) {
  selectedFile.value = (event.target as HTMLInputElement).files?.[0]
}

async function uploadFile() {
  if (!activeProject.value || !selectedFile.value) return
  try {
    uploadedFile.value = await uploadProjectFile(activeProject.value.id, selectedFile.value)
    ElMessage.success('文件已进入安全隔离区')
  } catch {
    ElMessage.error('上传失败，请检查类型、大小和文件内容')
  }
}

async function refreshAudits() {
  try {
    audits.value = await listAudits()
  } catch {
    ElMessage.error('无权读取审计日志')
  }
}
</script>

<template>
  <el-card class="panel stage-one">
    <template #header>
      <div class="card-heading">
        <strong>工程工作台 · 课题与研究 Agent</strong>
        <el-tag type="success">
          医院隔离
        </el-tag>
      </div>
    </template>
    <template v-if="!user">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="医院编码（平台管理员留空）">
            <el-input v-model="hospitalCode" />
          </el-form-item>
          <el-form-item label="用户名">
            <el-input
              v-model="username"
              autocomplete="username"
            />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="password"
              type="password"
              autocomplete="current-password"
              show-password
            />
          </el-form-item>
        </div>
        <el-button
          type="primary"
          :loading="loading"
          @click="signIn"
        >
          登录
        </el-button>
      </el-form>
    </template>
    <template v-else-if="user.forcePasswordChange">
      <el-alert
        title="首次登录必须修改初始密码"
        type="warning"
        :closable="false"
      />
      <div class="form-grid password-form">
        <el-input
          v-model="currentPassword"
          type="password"
          placeholder="当前密码"
          show-password
        />
        <el-input
          v-model="newPassword"
          type="password"
          placeholder="新密码（至少12位）"
          show-password
        />
      </div>
      <el-button
        type="primary"
        @click="updatePassword"
      >
        修改密码并注销现有会话
      </el-button>
    </template>
    <template v-else>
      <p>当前用户：{{ user.username }} · {{ user.roles.join(' / ') }}</p>
      <div class="form-grid">
        <el-input
          v-model="projectCode"
          placeholder="课题编码"
        />
        <el-input
          v-model="projectName"
          placeholder="课题名称"
        />
        <el-button
          type="primary"
          @click="addProject"
        >
          创建课题
        </el-button>
      </div>
      <el-table
        :data="projects"
        empty-text="本院暂无课题"
      >
        <el-table-column
          prop="code"
          label="课题编码"
          width="180"
        />
        <el-table-column
          prop="name"
          label="课题名称"
        />
        <el-table-column
          prop="version"
          label="版本"
          width="90"
        />
        <el-table-column
          label="操作"
          width="110"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="manageProject(row)"
            >
              成员/文件
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <template v-if="activeProject">
        <el-divider content-position="left">
          {{ activeProject.name }} · 成员
        </el-divider>
        <div class="form-grid">
          <el-input
            v-model="memberUserId"
            placeholder="本院用户 UUID"
          />
          <el-select v-model="memberRole">
            <el-option
              label="查看者"
              value="VIEWER"
            />
            <el-option
              label="编辑者"
              value="EDITOR"
            />
            <el-option
              label="所有者"
              value="OWNER"
            />
          </el-select>
          <el-button @click="addMember">
            添加成员
          </el-button>
        </div>
        <el-table
          :data="members"
          size="small"
          empty-text="暂无可见成员"
        >
          <el-table-column
            prop="username"
            label="用户名"
          />
          <el-table-column
            prop="userId"
            label="用户 ID"
          />
          <el-table-column
            prop="role"
            label="课题角色"
            width="110"
          />
        </el-table>
        <el-divider content-position="left">
          安全文件上传
        </el-divider>
        <div class="form-grid">
          <input
            type="file"
            accept=".pdf,.docx,.txt,.md"
            @change="chooseFile"
          >
          <el-button
            type="primary"
            :disabled="!selectedFile"
            @click="uploadFile"
          >
            上传到隔离区
          </el-button>
        </div>
        <el-alert
          v-if="uploadedFile"
          :title="`${uploadedFile.originalName}：${uploadedFile.securityStatus}`"
          :type="uploadedFile.canSendToExternalModel ? 'success' : 'warning'"
          :closable="false"
          show-icon
        />
        <el-descriptions
          v-if="uploadedFile"
          :column="3"
          border
          class="file-result"
        >
          <el-descriptions-item label="扫描引擎">
            {{ uploadedFile.scanEngine }}
          </el-descriptions-item>
          <el-descriptions-item label="文本提取">
            {{ uploadedFile.extractionStatus }}
          </el-descriptions-item>
          <el-descriptions-item label="提取字符">
            {{ uploadedFile.extractedCharacters }}
          </el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">
          阶段 2 · 持久化研究 Agent
        </el-divider>
        <el-input
          v-model="researchIdea"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          placeholder="输入匿名研究想法；不要填写患者姓名、住院号或其他可识别信息"
        />
        <div class="agent-actions">
          <el-button
            type="primary"
            :disabled="!researchIdea.trim() || ['QUEUED', 'RUNNING'].includes(agentTask?.status ?? '')"
            @click="startAgent"
          >
            启动后台任务
          </el-button>
          <el-button
            v-if="agentTask && ['QUEUED', 'RUNNING', 'WAITING_CONFIRMATION'].includes(agentTask.status)"
            @click="cancelTask"
          >
            取消任务
          </el-button>
          <el-button
            v-if="agentTask?.status === 'FAILED'"
            type="warning"
            @click="retryTask"
          >
            重试任务
          </el-button>
          <el-tag v-if="agentTask">
            {{ agentTask.status }} · {{ agentTask.currentStep }}
          </el-tag>
        </div>
        <template
          v-if="agentTask?.status === 'WAITING_CONFIRMATION'
            && agentTask.currentStep === 'STEP_03_ASK_CLARIFICATION'"
        >
          <el-alert
            title="信息不完整，回答以下问题后才会生成研究方向"
            type="warning"
            :closable="false"
          />
          <el-form
            label-position="top"
            class="clarification-form"
          >
            <el-form-item
              v-for="question in agentTask.output?.clarificationQuestions"
              :key="question"
              :label="question"
            >
              <el-input
                v-model="clarificationAnswers[question]"
                type="textarea"
                :rows="2"
                maxlength="1000"
                show-word-limit
              />
            </el-form-item>
            <el-button
              type="primary"
              @click="submitClarifications"
            >
              提交澄清信息并生成方向
            </el-button>
          </el-form>
        </template>
        <template
          v-if="agentTask?.status === 'WAITING_CONFIRMATION'
            && agentTask.currentStep === 'STEP_07_BUILD_SEARCH_STRATEGY'
            && agentTask.output?.searchStrategy"
        >
          <el-alert
            title="请复核并确认 PubMed 检索策略；确认前不会执行真实文献检索"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-descriptions
            :column="2"
            border
            class="file-result"
          >
            <el-descriptions-item label="数据库">
              {{ agentTask.output.searchStrategy.databases.join(' / ') }}
            </el-descriptions-item>
            <el-descriptions-item label="检索式版本">
              {{ agentTask.output.searchStrategy.queryVersion }}
            </el-descriptions-item>
            <el-descriptions-item
              label="原始研究问题"
              :span="2"
            >
              {{ agentTask.output.searchStrategy.originalResearchQuestion }}
            </el-descriptions-item>
          </el-descriptions>
          <el-table
            :data="agentTask.output.searchStrategy.concepts"
            size="small"
            class="file-result"
          >
            <el-table-column
              prop="label"
              label="结构化概念"
              width="130"
            />
            <el-table-column label="检索词">
              <template #default="{ row }">
                {{ row.terms.join('；') }}
              </template>
            </el-table-column>
            <el-table-column
              label="必需"
              width="80"
            >
              <template #default="{ row }">
                {{ row.required ? '是' : '否' }}
              </template>
            </el-table-column>
          </el-table>
          <el-form
            label-position="top"
            class="clarification-form"
          >
            <el-form-item label="可修订的 PubMed 检索式">
              <el-input
                v-model="searchQuery"
                type="textarea"
                :rows="9"
                maxlength="4000"
                show-word-limit
              />
            </el-form-item>
            <el-button
              type="primary"
              :disabled="!searchQuery.trim()"
              @click="confirmSearchStrategy"
            >
              确认检索策略并完成本步骤
            </el-button>
          </el-form>
          <el-alert
            v-for="limitation in agentTask.output.searchStrategy.limitations"
            :key="limitation"
            :title="limitation"
            type="info"
            :closable="false"
            class="strategy-limit"
          />
        </template>
        <template
          v-if="agentTask?.status === 'WAITING_CONFIRMATION'
            && agentTask.currentStep === 'STEP_05_CONFIRM_DIRECTION'"
        >
          <el-alert
            title="请选择研究方向后继续，后台任务不会因关闭浏览器而停止"
            type="info"
            :closable="false"
          />
          <div class="direction-grid">
            <el-card
              v-for="direction in agentTask.output?.directions"
              :key="direction.id"
              shadow="never"
            >
              <strong>{{ direction.title }}</strong>
              <p>{{ direction.recommendedStudyType }}</p>
              <el-button
                type="primary"
                @click="confirmDirection(direction.id)"
              >
                确认此方向
              </el-button>
            </el-card>
          </div>
          <el-divider content-position="left">
            需要修订研究信息？
          </el-divider>
          <el-alert
            title="修改下列答案后可重新生成方向，旧方向会保留审计记录但不能继续确认"
            type="warning"
            :closable="false"
          />
          <el-form
            label-position="top"
            class="clarification-form"
          >
            <el-form-item
              v-for="question in agentTask.output?.clarificationQuestions"
              :key="question"
              :label="question"
            >
              <el-input
                v-model="clarificationAnswers[question]"
                type="textarea"
                :rows="2"
                maxlength="1000"
                show-word-limit
              />
            </el-form-item>
            <el-button
              type="warning"
              @click="submitClarifications"
            >
              保存新一轮澄清并重新生成方向
            </el-button>
          </el-form>
        </template>
        <el-collapse
          v-if="clarificationHistory.length"
          class="clarification-history"
        >
          <el-collapse-item
            v-for="round in clarificationHistory"
            :key="round.id"
            :name="round.roundNo"
            :title="`第 ${round.roundNo} 轮澄清 · ${formatTime(round.submittedAt)}`"
          >
            <div
              v-for="question in round.questions"
              :key="question"
              class="clarification-history-item"
            >
              <strong>{{ question }}</strong>
              <p>{{ round.answers[question] }}</p>
            </div>
          </el-collapse-item>
        </el-collapse>
        <template
          v-if="agentTask?.status === 'WAITING_CONFIRMATION'
            && agentTask.currentStep === 'STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN'
            && agentTask.output?.observationalDesignRecommendation"
        >
          <el-divider content-position="left">
            STEP12 观察性研究设计推荐与人工确认
          </el-divider>
          <el-alert
            title="系统只提供版本化设计建议。研究类型、主要终点及进入正式方案生成的授权必须由有编辑权限的人员确认。"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-descriptions
            :column="3"
            border
            class="file-result"
          >
            <el-descriptions-item label="推荐类型">
              {{ agentTask.output.observationalDesignRecommendation.recommendedStudyType }}
            </el-descriptions-item>
            <el-descriptions-item label="规则版本">
              {{ agentTask.output.observationalDesignRecommendation.algorithmVersion }}
            </el-descriptions-item>
            <el-descriptions-item label="方案准备状态">
              <el-tag
                :type="agentTask.output.observationalDesignRecommendation.readyForProtocolDraft
                  ? 'success' : 'warning'"
              >
                {{
                  agentTask.output.observationalDesignRecommendation.readyForProtocolDraft
                    ? '信息完整' : '仍需澄清'
                }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item
              label="输入 SHA-256"
              :span="3"
            >
              <code>{{
                agentTask.output.observationalDesignRecommendation.inputSha256
              }}</code>
            </el-descriptions-item>
          </el-descriptions>
          <el-table
            :data="agentTask.output.observationalDesignRecommendation.alternatives"
            size="small"
            class="file-result"
          >
            <el-table-column
              prop="rank"
              label="排序"
              width="70"
            />
            <el-table-column
              prop="studyType"
              label="研究类型"
              width="160"
            />
            <el-table-column
              prop="score"
              label="规则评分"
              width="100"
            />
            <el-table-column
              prop="feasibilityStatus"
              label="可行性"
              width="170"
            />
            <el-table-column
              prop="rationale"
              label="推荐依据"
              min-width="300"
            />
            <el-table-column
              label="主要偏倚风险"
              min-width="280"
            >
              <template #default="{ row }">
                <el-tag
                  v-for="risk in row.biasRisks"
                  :key="risk"
                  type="warning"
                  class="risk-tag"
                >
                  {{ risk }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-alert
            v-for="item in agentTask.output.observationalDesignRecommendation.unresolvedItems"
            :key="item"
            :title="`待解决：${item}`"
            type="warning"
            :closable="false"
            class="strategy-limit"
          />
          <el-form
            label-position="top"
            class="clarification-form"
          >
            <el-form-item label="人工确认观察性研究类型">
              <el-radio-group v-model="designStudyType">
                <el-radio-button
                  v-for="alternative in agentTask.output.observationalDesignRecommendation.alternatives"
                  :key="alternative.studyType"
                  :value="alternative.studyType"
                >
                  {{ alternative.studyType }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="人工确认或修订主要终点">
              <el-input
                v-model="primaryOutcome"
                type="textarea"
                :rows="3"
                maxlength="1000"
                show-word-limit
              />
            </el-form-item>
            <el-checkbox v-model="protocolGenerationAuthorized">
              我已复核研究类型与主要终点，并授权进入正式研究方案生成
            </el-checkbox>
            <div class="design-confirm-actions">
              <el-button
                type="primary"
                :disabled="!agentTask.output.observationalDesignRecommendation.readyForProtocolDraft
                  || !primaryOutcome.trim()
                  || !protocolGenerationAuthorized"
                @click="confirmObservationalDesign"
              >
                确认设计并授权下一阶段
              </el-button>
            </div>
          </el-form>
          <el-alert
            v-for="limitation in agentTask.output.observationalDesignRecommendation.limitations"
            :key="limitation"
            :title="limitation"
            type="info"
            :closable="false"
            class="strategy-limit"
          />
        </template>
        <el-alert
          v-if="agentTask?.output?.peco"
          :title="agentTask.output?.peco?.researchQuestion"
          type="success"
          :closable="false"
          show-icon
        />
        <el-descriptions
          v-if="agentTask?.output?.observationalDesignRecommendation
            && agentTask.output.observationalDesignRecommendation.confirmationStatus === 'CONFIRMED'"
          :column="3"
          border
          class="file-result"
        >
          <el-descriptions-item label="已确认研究类型">
            {{
              agentTask.output.observationalDesignRecommendation.confirmedStudyType
            }}
          </el-descriptions-item>
          <el-descriptions-item label="已确认主要终点">
            {{
              agentTask.output.observationalDesignRecommendation.confirmedPrimaryOutcome
            }}
          </el-descriptions-item>
          <el-descriptions-item label="正式方案生成授权">
            {{
              agentTask.output.observationalDesignRecommendation.protocolGenerationAuthorized
                ? '已授权' : '未授权'
            }}
          </el-descriptions-item>
        </el-descriptions>
        <template
          v-if="agentTask?.output?.protocolDraft"
        >
          <el-divider content-position="left">
            STEP13 分章节观察性研究方案草案
          </el-divider>
          <el-alert
            title="以下内容是可追溯的初始章节版本，不是已经通过医学、统计学、伦理或科研管理审核的正式方案。"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-descriptions
            :column="3"
            border
            class="file-result"
          >
            <el-descriptions-item
              label="方案标题"
              :span="3"
            >
              {{ agentTask.output.protocolDraft.title }}
            </el-descriptions-item>
            <el-descriptions-item label="研究类型">
              {{ agentTask.output.protocolDraft.studyType }}
            </el-descriptions-item>
            <el-descriptions-item label="生成器版本">
              {{ agentTask.output.protocolDraft.generatorVersion }}
            </el-descriptions-item>
            <el-descriptions-item label="章节数">
              {{ agentTask.output.protocolDraft.sections.length }}
            </el-descriptions-item>
            <el-descriptions-item
              label="输入 SHA-256"
              :span="3"
            >
              <code>{{ agentTask.output.protocolDraft.inputSha256 }}</code>
            </el-descriptions-item>
          </el-descriptions>
          <el-collapse class="clarification-history">
            <el-collapse-item
              v-for="section in agentTask.output.protocolDraft.sections"
              :key="section.sectionId"
              :name="section.sectionCode"
              :title="`${section.sortOrder}. ${section.title} · v${section.versionNo}`"
            >
              <div class="protocol-section-meta">
                <el-tag size="small">
                  {{ section.origin }}
                </el-tag>
                <el-tag
                  size="small"
                  :type="section.evidenceStatus === 'DOCTOR_CONFIRMED_INPUT'
                    || section.evidenceStatus === 'VERIFIED_METADATA'
                    ? 'success' : 'warning'"
                >
                  {{ section.evidenceStatus }}
                </el-tag>
              </div>
              <pre class="confirmed-query">{{ section.content }}</pre>
              <p v-if="section.sourceIdentifiers.length">
                <strong>生成依据：</strong>
                {{ section.sourceIdentifiers.join(' / ') }}
              </p>
              <el-alert
                v-for="issue in section.issuesToConfirm"
                :key="issue"
                :title="`待确认：${issue}`"
                type="warning"
                :closable="false"
                class="strategy-limit"
              />
            </el-collapse-item>
          </el-collapse>
          <el-alert
            v-for="issue in agentTask.output.protocolDraft.issuesToConfirm"
            :key="issue"
            :title="`方案级待确认：${issue}`"
            type="warning"
            :closable="false"
            class="strategy-limit"
          />
          <el-alert
            v-for="limitation in agentTask.output.protocolDraft.limitations"
            :key="limitation"
            :title="limitation"
            type="info"
            :closable="false"
            class="strategy-limit"
          />
        </template>
        <template
          v-if="agentTask?.output?.statisticalAnalysisDraft"
        >
          <el-divider content-position="left">
            STEP14 统计分析计划草案
          </el-divider>
          <el-alert
            title="本步骤只生成待统计学专家复核的分析计划与参数清单；所有样本量参数仍为待输入，未计算、未猜测最终样本量。"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-descriptions
            :column="3"
            border
            class="file-result"
          >
            <el-descriptions-item label="研究类型">
              {{ agentTask.output.statisticalAnalysisDraft.studyType }}
            </el-descriptions-item>
            <el-descriptions-item label="终点类型状态">
              {{ agentTask.output.statisticalAnalysisDraft.outcomeTypeStatus }}
            </el-descriptions-item>
            <el-descriptions-item label="章节版本">
              v{{ agentTask.output.statisticalAnalysisDraft.statisticalSectionVersion.versionNo }}
            </el-descriptions-item>
            <el-descriptions-item
              label="主要终点"
              :span="3"
            >
              {{ agentTask.output.statisticalAnalysisDraft.primaryOutcome }}
            </el-descriptions-item>
            <el-descriptions-item
              label="确定性生成器"
              :span="3"
            >
              {{ agentTask.output.statisticalAnalysisDraft.generatorVersion }}
            </el-descriptions-item>
          </el-descriptions>
          <el-collapse class="clarification-history">
            <el-collapse-item title="描述性统计">
              <ul>
                <li
                  v-for="item in agentTask.output.statisticalAnalysisDraft.descriptiveAnalysis"
                  :key="item"
                >
                  {{ item }}
                </li>
              </ul>
            </el-collapse-item>
            <el-collapse-item title="主要与次要分析候选">
              <p><strong>主要分析：</strong></p>
              <ul>
                <li
                  v-for="item in agentTask.output.statisticalAnalysisDraft.primaryAnalysisCandidates"
                  :key="item"
                >
                  {{ item }}
                </li>
              </ul>
              <p><strong>次要分析：</strong></p>
              <ul>
                <li
                  v-for="item in agentTask.output.statisticalAnalysisDraft.secondaryAnalysis"
                  :key="item"
                >
                  {{ item }}
                </li>
              </ul>
            </el-collapse-item>
            <el-collapse-item title="缺失数据、多重比较与模型诊断">
              <ul>
                <li
                  v-for="item in [
                    ...agentTask.output.statisticalAnalysisDraft.missingDataPlan,
                    ...agentTask.output.statisticalAnalysisDraft.multipleComparisonPlan,
                    ...agentTask.output.statisticalAnalysisDraft.modelDiagnostics,
                  ]"
                  :key="item"
                >
                  {{ item }}
                </li>
              </ul>
            </el-collapse-item>
            <el-collapse-item title="分层、亚组与敏感性分析">
              <ul>
                <li
                  v-for="item in [
                    ...agentTask.output.statisticalAnalysisDraft.stratifiedAnalyses,
                    ...agentTask.output.statisticalAnalysisDraft.subgroupAnalyses,
                    ...agentTask.output.statisticalAnalysisDraft.sensitivityAnalyses,
                  ]"
                  :key="item"
                >
                  {{ item }}
                </li>
              </ul>
            </el-collapse-item>
          </el-collapse>
          <p><strong>候选效应量：</strong></p>
          <el-space wrap>
            <el-tag
              v-for="item in agentTask.output.statisticalAnalysisDraft.effectMeasureCandidates"
              :key="item"
            >
              {{ item }}
            </el-tag>
          </el-space>
          <el-alert
            :title="agentTask.output.statisticalAnalysisDraft.confidenceIntervalPlan"
            type="info"
            :closable="false"
            class="strategy-limit"
          />
          <h4>样本量计算参数清单</h4>
          <el-table
            :data="agentTask.output.statisticalAnalysisDraft.sampleSizeParameters"
            border
            class="file-result"
          >
            <el-table-column
              prop="label"
              label="参数"
              min-width="190"
            />
            <el-table-column
              prop="valueStatus"
              label="状态"
              min-width="170"
            />
            <el-table-column
              prop="value"
              label="当前值"
              min-width="100"
            >
              <template #default="{ row }">
                {{ row.value ?? '未提供' }}
              </template>
            </el-table-column>
            <el-table-column
              prop="rationale"
              label="用途与确认依据"
              min-width="280"
            />
          </el-table>
          <el-alert
            v-for="issue in agentTask.output.statisticalAnalysisDraft.issuesToConfirm"
            :key="issue"
            :title="`统计学专家待确认：${issue}`"
            type="warning"
            :closable="false"
            class="strategy-limit"
          />
          <el-alert
            v-for="limitation in agentTask.output.statisticalAnalysisDraft.limitations"
            :key="limitation"
            :title="limitation"
            type="info"
            :closable="false"
            class="strategy-limit"
          />
        </template>
        <template
          v-if="agentTask?.output?.claimCitationValidation"
        >
          <el-divider content-position="left">
            STEP15 研究主张—引用依据验证
          </el-divider>
          <el-alert
            title="当前仅有摘要级公开证据。ABSTRACT_ONLY 表示已定位候选摘要依据，不表示主张已被充分支持；必须完成全文审阅和专家确认。"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-descriptions
            :column="4"
            border
            class="file-result"
          >
            <el-descriptions-item label="主张数">
              {{ agentTask.output.claimCitationValidation.claimCount }}
            </el-descriptions-item>
            <el-descriptions-item label="引用链接数">
              {{ agentTask.output.claimCitationValidation.citationLinkCount }}
            </el-descriptions-item>
            <el-descriptions-item label="摘要级主张">
              {{ agentTask.output.claimCitationValidation.abstractOnlyClaimCount }}
            </el-descriptions-item>
            <el-descriptions-item label="待专家复核">
              {{ agentTask.output.claimCitationValidation.needsExpertReviewClaimCount }}
            </el-descriptions-item>
            <el-descriptions-item
              label="验证器版本"
              :span="4"
            >
              {{ agentTask.output.claimCitationValidation.validatorVersion }}
            </el-descriptions-item>
          </el-descriptions>
          <el-collapse class="clarification-history">
            <el-collapse-item
              v-for="claim in agentTask.output.claimCitationValidation.claims"
              :key="claim.claimId"
              :name="claim.claimId"
              :title="`${claim.sectionCode} · 主张 ${claim.claimOrder} · ${claim.supportStatus}`"
            >
              <p>{{ claim.claimText }}</p>
              <el-space wrap>
                <el-tag
                  :type="claim.supportStatus === 'ABSTRACT_ONLY' ? 'warning' : 'danger'"
                >
                  {{ claim.supportStatus }}
                </el-tag>
                <el-tag type="info">
                  {{ claim.expertConfirmationStatus }}
                </el-tag>
              </el-space>
              <el-table
                v-if="claim.citationLinks.length"
                :data="claim.citationLinks"
                border
                class="file-result"
              >
                <el-table-column
                  prop="pmid"
                  label="PMID"
                  width="120"
                />
                <el-table-column
                  prop="doi"
                  label="DOI"
                  min-width="180"
                />
                <el-table-column
                  prop="title"
                  label="已核验题名"
                  min-width="260"
                />
                <el-table-column
                  prop="evidenceScope"
                  label="证据范围"
                  width="150"
                />
                <el-table-column
                  prop="citationValidationStatus"
                  label="元数据核验"
                  width="150"
                />
              </el-table>
              <div
                v-for="link in claim.citationLinks"
                :key="`${link.linkId}-excerpt`"
                class="strategy-limit"
              >
                <strong>依据片段（{{ link.excerptLocation }}）：</strong>
                <p>{{ link.evidenceExcerpt }}</p>
                <small>SHA-256：{{ link.excerptSha256 }}</small>
              </div>
              <el-alert
                v-for="issue in claim.issuesToConfirm"
                :key="issue"
                :title="issue"
                type="warning"
                :closable="false"
                class="strategy-limit"
              />
            </el-collapse-item>
          </el-collapse>
          <el-alert
            v-for="limitation in agentTask.output.claimCitationValidation.limitations"
            :key="limitation"
            :title="limitation"
            type="info"
            :closable="false"
            class="strategy-limit"
          />
        </template>
        <template
          v-if="agentTask?.output?.strobeCompletenessCheck"
        >
          <el-divider content-position="left">
            STEP16 STROBE 报告完整性预检查
          </el-divider>
          <el-alert
            :title="agentTask.output.strobeCompletenessCheck.automaticPrecheckDisclaimer"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-descriptions
            :column="3"
            border
            class="file-result"
          >
            <el-descriptions-item label="规范版本">
              {{ agentTask.output.strobeCompletenessCheck.guidelineVersion }}
            </el-descriptions-item>
            <el-descriptions-item label="研究类型">
              {{ agentTask.output.strobeCompletenessCheck.studyType }}
            </el-descriptions-item>
            <el-descriptions-item label="主条目总数">
              {{ agentTask.output.strobeCompletenessCheck.totalItemCount }}
            </el-descriptions-item>
            <el-descriptions-item label="已覆盖">
              {{ agentTask.output.strobeCompletenessCheck.coveredCount }}
            </el-descriptions-item>
            <el-descriptions-item label="部分覆盖">
              {{ agentTask.output.strobeCompletenessCheck.partiallyCoveredCount }}
            </el-descriptions-item>
            <el-descriptions-item label="缺失">
              {{ agentTask.output.strobeCompletenessCheck.missingCount }}
            </el-descriptions-item>
            <el-descriptions-item label="不适用">
              {{ agentTask.output.strobeCompletenessCheck.notApplicableCount }}
            </el-descriptions-item>
            <el-descriptions-item label="待专家复核">
              {{ agentTask.output.strobeCompletenessCheck.needsExpertReviewCount }}
            </el-descriptions-item>
            <el-descriptions-item label="检查器版本">
              {{ agentTask.output.strobeCompletenessCheck.checkerVersion }}
            </el-descriptions-item>
            <el-descriptions-item
              label="官方清单"
              :span="3"
            >
              <a
                :href="agentTask.output.strobeCompletenessCheck.sourceReference"
                target="_blank"
                rel="noreferrer"
              >
                STROBE 官方检查表
              </a>
            </el-descriptions-item>
          </el-descriptions>
          <el-collapse class="clarification-history">
            <el-collapse-item
              v-for="item in agentTask.output.strobeCompletenessCheck.items"
              :key="item.itemResultId"
              :name="item.itemCode"
              :title="`${item.itemCode} · ${item.sectionGroup} · ${item.status}`"
            >
              <p>{{ item.requirementSummary }}</p>
              <el-tag
                :type="item.status === 'COVERED'
                  ? 'success'
                  : item.status === 'MISSING'
                    ? 'danger' : 'warning'"
              >
                {{ item.status }}
              </el-tag>
              <p>
                <strong>映射章节：</strong>
                {{ item.mappedSectionCodes.length
                  ? item.mappedSectionCodes.join(' / ') : '当前无对应章节' }}
              </p>
              <p><strong>检查说明：</strong>{{ item.message }}</p>
              <p><strong>补充建议：</strong>{{ item.suggestion }}</p>
              <el-alert
                v-if="item.requiresExpertReview"
                title="该条目仍需专家复核"
                type="warning"
                :closable="false"
                class="strategy-limit"
              />
              <details v-if="item.evidenceSnippets.length">
                <summary>查看自动匹配的方案片段</summary>
                <pre
                  v-for="snippet in item.evidenceSnippets"
                  :key="snippet"
                  class="confirmed-query"
                >{{ snippet }}</pre>
              </details>
            </el-collapse-item>
          </el-collapse>
          <el-alert
            v-for="limitation in agentTask.output.strobeCompletenessCheck.limitations"
            :key="limitation"
            :title="limitation"
            type="info"
            :closable="false"
            class="strategy-limit"
          />
        </template>
        <template v-if="expertReview">
          <el-divider content-position="left">
            STEP17 专家审核工作台
          </el-divider>
          <el-alert
            title="审核结论由有权限的专家作出；专家通过后仍需课题负责人确认，确认时锁定当前章节版本。"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-descriptions
            :column="3"
            border
            class="file-result"
          >
            <el-descriptions-item label="审核状态">
              {{ expertReview.status }}
            </el-descriptions-item>
            <el-descriptions-item label="审核版本">
              {{ expertReview.version }}
            </el-descriptions-item>
            <el-descriptions-item label="章节已锁定">
              {{ expertReview.sectionsLocked ? '是' : '否' }}
            </el-descriptions-item>
            <el-descriptions-item
              v-if="expertReview.expertSummary"
              label="专家结论"
              :span="3"
            >
              {{ expertReview.expertSummary }}
            </el-descriptions-item>
          </el-descriptions>

          <section
            v-if="isExpert && expertReview.status === 'WAITING_EXPERT_REVIEW'"
            class="review-editor"
          >
            <h4>添加版本化专家批注</h4>
            <div class="form-grid">
              <el-select
                v-model="reviewTargetType"
                aria-label="批注目标类型"
              >
                <el-option
                  label="方案章节版本"
                  value="SECTION"
                />
                <el-option
                  label="STROBE 条目"
                  value="STROBE"
                />
              </el-select>
              <el-select
                v-if="reviewTargetType === 'SECTION'"
                v-model="reviewSectionId"
                aria-label="方案章节版本"
              >
                <el-option
                  v-for="section in agentTask?.output?.protocolDraft?.sections"
                  :key="section.sectionId"
                  :label="`${section.sectionCode} · v${section.versionNo} · ${section.title}`"
                  :value="section.sectionId"
                />
              </el-select>
              <el-select
                v-else
                v-model="reviewStrobeItemId"
                aria-label="STROBE 条目"
              >
                <el-option
                  v-for="item in agentTask?.output?.strobeCompletenessCheck?.items"
                  :key="item.itemResultId"
                  :label="`${item.itemCode} · ${item.status} · ${item.requirementSummary}`"
                  :value="item.itemResultId"
                />
              </el-select>
              <el-select
                v-model="reviewCommentType"
                aria-label="批注专业类型"
              >
                <el-option
                  label="医学"
                  value="MEDICAL"
                />
                <el-option
                  label="统计学"
                  value="STATISTICAL"
                />
                <el-option
                  label="报告规范"
                  value="REPORTING"
                />
                <el-option
                  label="综合"
                  value="GENERAL"
                />
              </el-select>
            </div>
            <el-input
              v-model="reviewComment"
              type="textarea"
              :rows="3"
              maxlength="2000"
              show-word-limit
              placeholder="写明具体问题、修改要求和确认依据"
            />
            <el-button
              type="primary"
              @click="addReviewComment"
            >
              保存专家批注
            </el-button>

            <h4>提交专家审核决定</h4>
            <el-input
              v-model="reviewDecisionSummary"
              type="textarea"
              :rows="3"
              maxlength="2000"
              show-word-limit
              placeholder="填写审核总结；退回修改前至少需要一条定位批注"
            />
            <el-button
              type="danger"
              plain
              @click="decideReview('RETURN_FOR_REVISION')"
            >
              退回修改
            </el-button>
            <el-button
              type="success"
              @click="decideReview('APPROVE')"
            >
              专家审核通过
            </el-button>
          </section>

          <el-button
            v-if="isProjectOwner && expertReview.status === 'EXPERT_APPROVED'"
            type="primary"
            class="file-result"
            @click="ownerConfirmReview"
          >
            课题负责人确认并锁定当前章节版本
          </el-button>

          <h4>不可变审核批注</h4>
          <el-empty
            v-if="expertReview.comments.length === 0"
            description="尚无专家批注"
          />
          <el-card
            v-for="comment in expertReview.comments"
            :key="comment.id"
            shadow="never"
            class="strategy-limit"
          >
            <el-tag>{{ comment.commentType }}</el-tag>
            <span v-if="comment.protocolSectionId">
              章节 {{ comment.protocolSectionId }} · v{{ comment.protocolSectionVersionNo }}
            </span>
            <span v-else>
              STROBE 条目 {{ comment.strobeItemResultId }}
            </span>
            <p>{{ comment.content }}</p>
            <small>{{ formatTime(comment.createdAt) }}</small>
          </el-card>

          <h4>审核历史</h4>
          <el-timeline>
            <el-timeline-item
              v-for="action in expertReview.history"
              :key="action.id"
              :timestamp="formatTime(action.occurredAt)"
            >
              <strong>{{ action.actionType }}</strong>
              <p v-if="action.summary">
                {{ action.summary }}
              </p>
            </el-timeline-item>
          </el-timeline>
        </template>
        <template
          v-if="agentTask
            && (agentTask.currentStep === 'STEP_18_EXPORT_DOCUMENT'
              || agentTask.output?.documentExport)"
        >
          <el-divider content-position="left">
            STEP18 受控 Word 导出
          </el-divider>
          <el-alert
            title="正式导出只允许使用已发布的医院模板、已审核锁定的方案快照和系统核验过的引用记录。"
            type="warning"
            :closable="false"
            show-icon
          />

          <section class="review-editor">
            <div class="card-heading">
              <h4>医院模板版本</h4>
              <el-button
                v-if="isHospitalAdmin && documentTemplates.length === 0"
                type="primary"
                plain
                @click="installDefaultTemplate"
              >
                安装内置受控模板
              </el-button>
            </div>
            <div
              v-if="isHospitalAdmin"
              class="form-grid"
            >
              <el-input
                v-model="documentTemplateCode"
                aria-label="模板代码"
                placeholder="模板代码，例如 OBSERVATIONAL_PROTOCOL"
              />
              <el-input
                v-model="documentTemplateName"
                aria-label="模板名称"
                placeholder="模板名称"
              />
              <input
                type="file"
                accept=".docx"
                aria-label="选择 DOCX 模板"
                @change="chooseDocumentTemplate"
              >
              <el-button
                :disabled="!documentTemplateFile
                  || !documentTemplateName.trim()
                  || !documentTemplateCode.trim()"
                @click="uploadTemplate"
              >
                上传并校验模板
              </el-button>
            </div>
            <el-empty
              v-if="documentTemplates.length === 0"
              description="尚无模板；医院管理员需先安装或上传并发布模板"
            />
            <el-table
              v-else
              :data="documentTemplates"
              stripe
            >
              <el-table-column
                prop="templateCode"
                label="模板代码"
                min-width="170"
              />
              <el-table-column
                prop="templateName"
                label="模板名称"
                min-width="190"
              />
              <el-table-column
                prop="versionNo"
                label="版本"
                width="80"
              />
              <el-table-column
                prop="status"
                label="状态"
                width="110"
              />
              <el-table-column
                label="占位符"
                min-width="180"
              >
                <template #default="{ row }">
                  {{ row.placeholders.length }} 个 · {{ row.placeholderSchemaVersion }}
                </template>
              </el-table-column>
              <el-table-column
                label="操作"
                width="210"
              >
                <template #default="{ row }">
                  <el-button
                    v-if="isHospitalAdmin"
                    size="small"
                    @click="previewTemplate(row)"
                  >
                    试生成
                  </el-button>
                  <el-button
                    v-if="isHospitalAdmin && row.status === 'VALIDATED'"
                    size="small"
                    type="success"
                    @click="publishTemplate(row)"
                  >
                    发布
                  </el-button>
                  <el-tag
                    v-else-if="row.status === 'PUBLISHED'"
                    type="success"
                  >
                    正式可用
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </section>

          <section class="review-editor">
            <div class="card-heading">
              <h4>医院引用格式版本</h4>
              <el-button
                v-if="isHospitalAdmin && citationStyles.length === 0"
                type="primary"
                plain
                @click="installDefaultStyle"
              >
                安装内置数字格式
              </el-button>
            </div>
            <div
              v-if="isHospitalAdmin"
              class="form-grid"
            >
              <el-input
                v-model="citationStyleCode"
                aria-label="引用格式代码"
                placeholder="格式代码，例如 HOSPITAL_GBT"
              />
              <el-input
                v-model="citationStyleName"
                aria-label="引用格式名称"
                placeholder="医院引用格式名称"
              />
              <el-select
                v-model="citationLayout"
                aria-label="引用布局"
              >
                <el-option
                  label="Vancouver 数字格式"
                  value="VANCOUVER"
                />
                <el-option
                  label="GB/T 7714 数字格式"
                  value="GB_T_7714"
                />
              </el-select>
              <el-input-number
                v-model="citationAuthorLimit"
                aria-label="作者显示上限"
                :min="1"
                :max="20"
              />
              <el-input
                v-model="citationEtAlText"
                aria-label="作者省略标记"
                placeholder="作者省略标记，例如 等"
              />
              <el-input
                v-model="citationEvidenceScopeLabel"
                aria-label="证据范围标记"
                placeholder="证据范围标记"
              />
              <el-checkbox v-model="citationIncludeDoi">
                文档引用显示 DOI
              </el-checkbox>
              <el-checkbox v-model="citationIncludeEvidenceScope">
                文档引用显示证据范围
              </el-checkbox>
              <el-button
                :disabled="!citationStyleCode.trim()
                  || !citationStyleName.trim()
                  || !citationEtAlText.trim()
                  || !citationEvidenceScopeLabel.trim()"
                @click="saveCitationStyle"
              >
                创建引用格式版本
              </el-button>
            </div>
            <el-alert
              title="PMID 始终保留以保证引用可追溯；可配置布局、作者上限、DOI 和摘要证据标记。"
              type="info"
              :closable="false"
              show-icon
            />
            <el-empty
              v-if="citationStyles.length === 0"
              description="尚无引用格式；医院管理员需先创建并发布"
            />
            <el-table
              v-else
              :data="citationStyles"
              stripe
            >
              <el-table-column
                prop="styleCode"
                label="格式代码"
                min-width="160"
              />
              <el-table-column
                prop="styleName"
                label="名称"
                min-width="190"
              />
              <el-table-column
                prop="layout"
                label="布局"
                width="130"
              />
              <el-table-column
                prop="versionNo"
                label="版本"
                width="75"
              />
              <el-table-column
                prop="status"
                label="状态"
                width="110"
              />
              <el-table-column
                label="规则"
                min-width="210"
              >
                <template #default="{ row }">
                  作者 {{ row.authorLimit }} 人 · PMID 必显 ·
                  DOI {{ row.includeDoi ? '显示' : '隐藏' }}
                </template>
              </el-table-column>
              <el-table-column
                label="操作"
                width="140"
              >
                <template #default="{ row }">
                  <el-button
                    v-if="isHospitalAdmin && row.status === 'VALIDATED'"
                    size="small"
                    type="success"
                    @click="publishStyle(row)"
                  >
                    发布
                  </el-button>
                  <el-tag
                    v-else-if="row.status === 'PUBLISHED'"
                    type="success"
                  >
                    正式可用
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </section>

          <section
            v-if="!documentExport && agentTask.status === 'WAITING_CONFIRMATION'"
            class="review-editor"
          >
            <h4>课题负责人导出确认</h4>
            <el-select
              v-model="selectedTemplateId"
              placeholder="选择已发布模板版本"
              class="file-result"
            >
              <el-option
                v-for="documentTemplate in documentTemplates.filter(
                  (value) => value.status === 'PUBLISHED',
                )"
                :key="documentTemplate.id"
                :label="`${documentTemplate.templateName} · v${documentTemplate.versionNo}`"
                :value="documentTemplate.id"
              />
            </el-select>
            <el-select
              v-model="selectedCitationStyleId"
              placeholder="选择已发布医院引用格式"
              class="file-result"
            >
              <el-option
                v-for="citationStyle in citationStyles.filter(
                  (value) => value.status === 'PUBLISHED',
                )"
                :key="citationStyle.id"
                :label="`${citationStyle.styleName} · ${citationStyle.layout} · v${citationStyle.versionNo}`"
                :value="citationStyle.id"
              />
            </el-select>
            <el-checkbox v-model="confirmReviewedContent">
              我确认导出内容来自专家通过、课题负责人确认并锁定的方案版本
            </el-checkbox>
            <el-button
              type="primary"
              :disabled="!isProjectOwner
                || !selectedTemplateId
                || !selectedCitationStyleId
                || !confirmReviewedContent"
              @click="exportDocument"
            >
              确认并生成 Word 文档
            </el-button>
          </section>

          <el-descriptions
            v-if="documentExport"
            :column="2"
            border
            class="file-result"
          >
            <el-descriptions-item label="导出状态">
              {{ documentExport.status }}
            </el-descriptions-item>
            <el-descriptions-item label="引用数量">
              {{ documentExport.citationCount }}
            </el-descriptions-item>
            <el-descriptions-item label="引用格式">
              {{ documentExport.citationStyleCode }} ·
              {{ documentExport.citationStyleVersion }}
            </el-descriptions-item>
            <el-descriptions-item label="文件大小">
              {{ documentExport.contentSize }} bytes
            </el-descriptions-item>
            <el-descriptions-item
              label="文件 SHA-256"
              :span="2"
            >
              <code>{{ documentExport.contentSha256 }}</code>
            </el-descriptions-item>
            <el-descriptions-item
              label="方案快照 SHA-256"
              :span="2"
            >
              <code>{{ documentExport.protocolSnapshotSha256 }}</code>
            </el-descriptions-item>
            <el-descriptions-item
              label="下载"
              :span="2"
            >
              <el-link
                :href="documentExportDownloadUrl(documentExport.id)"
                type="primary"
              >
                {{ documentExport.fileName }}
              </el-link>
            </el-descriptions-item>
          </el-descriptions>
        </template>
        <el-descriptions
          v-if="agentTask?.output?.designAssessment"
          :column="2"
          border
          class="file-result"
        >
          <el-descriptions-item label="研究类型">
            {{ agentTask.output.designAssessment.studyType }}
          </el-descriptions-item>
          <el-descriptions-item label="规则版本">
            {{ agentTask.output.designAssessment.ruleVersion }}
          </el-descriptions-item>
          <el-descriptions-item
            label="规则解释"
            :span="2"
          >
            {{ agentTask.output.designAssessment.explanation }}
          </el-descriptions-item>
        </el-descriptions>
        <el-descriptions
          v-if="agentTask?.output?.searchStrategy
            && agentTask.output.searchStrategy.confirmationStatus === 'CONFIRMED'"
          :column="2"
          border
          class="file-result"
        >
          <el-descriptions-item label="检索策略状态">
            {{ agentTask.output.searchStrategy.confirmationStatus }}
          </el-descriptions-item>
          <el-descriptions-item label="检索式版本">
            {{ agentTask.output.searchStrategy.queryVersion }}
          </el-descriptions-item>
          <el-descriptions-item
            label="已确认 PubMed 检索式"
            :span="2"
          >
            <pre class="confirmed-query">{{ agentTask.output.searchStrategy.pubmedQuery }}</pre>
          </el-descriptions-item>
        </el-descriptions>
        <template v-if="agentTask?.output?.pubmedSearch">
          <el-descriptions
            :column="3"
            border
            class="file-result"
          >
            <el-descriptions-item label="数据源">
              {{ agentTask.output.pubmedSearch.database }}
            </el-descriptions-item>
            <el-descriptions-item label="命中 / 返回">
              {{ agentTask.output.pubmedSearch.totalResultCount }}
              / {{ agentTask.output.pubmedSearch.returnedCount }}
            </el-descriptions-item>
            <el-descriptions-item label="执行工具">
              {{ agentTask.output.pubmedSearch.toolVersion }}
            </el-descriptions-item>
            <el-descriptions-item
              label="原始响应 SHA-256"
              :span="3"
            >
              <code>{{ agentTask.output.pubmedSearch.rawResponseSha256 }}</code>
            </el-descriptions-item>
          </el-descriptions>
          <el-table
            :data="agentTask.output.pubmedSearch.records"
            size="small"
            class="file-result"
            empty-text="当前检索式未返回 PubMed 记录"
          >
            <el-table-column
              prop="pmid"
              label="PMID"
              width="110"
            />
            <el-table-column
              prop="title"
              label="标题"
              min-width="280"
            />
            <el-table-column
              prop="journal"
              label="期刊"
              min-width="180"
            />
            <el-table-column
              prop="publicationDate"
              label="发表日期"
              width="130"
            />
            <el-table-column
              label="证据范围"
              width="130"
            >
              <template #default="{ row }">
                <el-tag :type="row.verified ? 'success' : 'danger'">
                  {{ row.evidenceScope }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-alert
            v-for="limitation in agentTask.output.pubmedSearch.limitations"
            :key="limitation"
            :title="limitation"
            type="info"
            :closable="false"
            class="strategy-limit"
          />
        </template>
        <template
          v-if="agentTask?.output?.clinicalTrialsSearch"
        >
          <el-divider content-position="left">
            ClinicalTrials.gov 注册研究
          </el-divider>
          <el-alert
            title="以下内容是公开试验注册记录，不等同于同行评议发表证据"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-descriptions
            :column="3"
            border
            class="file-result"
          >
            <el-descriptions-item label="数据源">
              {{ agentTask.output.clinicalTrialsSearch.database }}
            </el-descriptions-item>
            <el-descriptions-item label="命中 / 返回">
              {{ agentTask.output.clinicalTrialsSearch.totalResultCount }}
              / {{ agentTask.output.clinicalTrialsSearch.returnedCount }}
            </el-descriptions-item>
            <el-descriptions-item label="API / 数据版本">
              {{ agentTask.output.clinicalTrialsSearch.toolVersion }}
              / {{ agentTask.output.clinicalTrialsSearch.dataVersion ?? '未提供' }}
            </el-descriptions-item>
            <el-descriptions-item label="缓存命中">
              {{ agentTask.output.clinicalTrialsSearch.cacheHit ? '是' : '否' }}
            </el-descriptions-item>
            <el-descriptions-item
              label="原始响应 SHA-256"
              :span="2"
            >
              <code>{{ agentTask.output.clinicalTrialsSearch.rawResponseSha256 }}</code>
            </el-descriptions-item>
            <el-descriptions-item
              label="ClinicalTrials.gov 检索式"
              :span="3"
            >
              <pre class="confirmed-query">{{
                agentTask.output.clinicalTrialsSearch.query
              }}</pre>
            </el-descriptions-item>
          </el-descriptions>
          <el-table
            :data="agentTask.output.clinicalTrialsSearch.records"
            size="small"
            class="file-result"
            empty-text="当前检索式未返回 ClinicalTrials.gov 注册记录"
          >
            <el-table-column
              label="NCT ID"
              width="145"
            >
              <template #default="{ row }">
                <el-link
                  :href="`https://clinicaltrials.gov/study/${row.nctId}`"
                  target="_blank"
                  rel="noopener noreferrer"
                  type="primary"
                >
                  {{ row.nctId }}
                </el-link>
              </template>
            </el-table-column>
            <el-table-column
              prop="briefTitle"
              label="注册题名"
              min-width="280"
            />
            <el-table-column
              prop="overallStatus"
              label="状态"
              width="145"
            />
            <el-table-column
              prop="studyType"
              label="研究类型"
              width="150"
            />
            <el-table-column
              prop="enrollment"
              label="入组数"
              width="90"
            />
            <el-table-column
              label="证据范围"
              width="190"
            >
              <template #default="{ row }">
                <el-tag :type="row.verified ? 'success' : 'danger'">
                  {{ row.evidenceScope }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-alert
            v-for="limitation in agentTask.output.clinicalTrialsSearch.limitations"
            :key="limitation"
            :title="limitation"
            type="info"
            :closable="false"
            class="strategy-limit"
          />
          <p class="source-attribution">
            本产品使用美国国家医学图书馆（NLM）公开数据；NLM 不对本产品负责，也不表示认可或推荐。
            This product uses publicly available data from the U.S. National Library of
            Medicine (NLM), National Institutes of Health, Department of Health and Human
            Services; NLM is not responsible for the product and does not endorse or recommend
            this or any other product.
          </p>
        </template>
        <template
          v-if="agentTask?.output?.literatureValidation"
        >
          <el-divider content-position="left">
            STEP10 文献真实性与跨来源关联验证
          </el-divider>
          <el-alert
            title="Crossref 校验只核对引文身份和元数据；不能替代全文审阅、质量评价或偏倚风险评价"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-descriptions
            :column="4"
            border
            class="file-result"
          >
            <el-descriptions-item label="已核验 / 总数">
              {{ agentTask.output.literatureValidation.verifiedCount }}
              / {{ agentTask.output.literatureValidation.totalCount }}
            </el-descriptions-item>
            <el-descriptions-item label="辅助元数据差异">
              {{ agentTask.output.literatureValidation.metadataDifferenceCount }}
            </el-descriptions-item>
            <el-descriptions-item label="核心字段不一致">
              {{ agentTask.output.literatureValidation.mismatchCount }}
            </el-descriptions-item>
            <el-descriptions-item label="未找到 / 无 DOI">
              {{ agentTask.output.literatureValidation.crossrefNotFoundCount }}
              / {{ agentTask.output.literatureValidation.doiNotAvailableCount }}
            </el-descriptions-item>
            <el-descriptions-item label="Crossref 工具">
              {{ agentTask.output.literatureValidation.toolVersion }}
            </el-descriptions-item>
            <el-descriptions-item label="外部请求 / 缓存命中">
              {{ agentTask.output.literatureValidation.externalRequestCount }}
              / {{ agentTask.output.literatureValidation.cacheHitCount }}
            </el-descriptions-item>
            <el-descriptions-item
              label="原始响应 SHA-256"
              :span="2"
            >
              <code>{{ agentTask.output.literatureValidation.rawResponseSha256 }}</code>
            </el-descriptions-item>
          </el-descriptions>
          <el-table
            :data="agentTask.output.literatureValidation.citations"
            size="small"
            class="file-result"
            empty-text="没有需要校验的 PubMed 引文"
          >
            <el-table-column
              prop="pmid"
              label="PMID"
              width="110"
            />
            <el-table-column
              label="DOI"
              min-width="210"
            >
              <template #default="{ row }">
                <el-link
                  v-if="row.doi"
                  :href="`https://doi.org/${row.doi}`"
                  target="_blank"
                  rel="noopener noreferrer"
                  type="primary"
                >
                  {{ row.doi }}
                </el-link>
                <span v-else>未提供</span>
              </template>
            </el-table-column>
            <el-table-column
              label="验证状态"
              min-width="240"
            >
              <template #default="{ row }">
                <el-tag
                  :type="row.status === 'VERIFIED'
                    ? 'success'
                    : row.status === 'VERIFIED_WITH_METADATA_DIFFERENCES'
                      ? 'warning'
                      : 'danger'"
                >
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="message"
              label="判定说明"
              min-width="300"
            />
          </el-table>
          <el-divider content-position="left">
            注册研究—论文关联
          </el-divider>
          <el-table
            :data="agentTask.output.literatureValidation.evidenceLinks"
            size="small"
            class="file-result"
            empty-text="注册记录未公开关联 PMID"
          >
            <el-table-column
              prop="nctId"
              label="NCT ID"
              width="150"
            />
            <el-table-column
              prop="pmid"
              label="PMID"
              width="130"
            />
            <el-table-column
              prop="relationship"
              label="关联依据"
              min-width="280"
            />
            <el-table-column
              label="解析状态"
              width="200"
            >
              <template #default="{ row }">
                <el-tag :type="row.status === 'RESOLVED' ? 'success' : 'warning'">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-alert
            v-for="limitation in agentTask.output.literatureValidation.limitations"
            :key="limitation"
            :title="limitation"
            type="info"
            :closable="false"
            class="strategy-limit"
          />
        </template>
        <template
          v-if="agentTask?.output?.similarResearchAnalysis"
        >
          <el-divider content-position="left">
            STEP11 相似研究与潜在研究空白
          </el-divider>
          <el-alert
            :title="agentTask.output.similarResearchAnalysis.conclusion"
            :type="agentTask.output.similarResearchAnalysis.highSimilarityCount > 0
              ? 'warning'
              : 'info'"
            :closable="false"
            show-icon
          />
          <el-descriptions
            :column="4"
            border
            class="file-result"
          >
            <el-descriptions-item label="分析来源">
              {{ agentTask.output.similarResearchAnalysis.analyzedSourceCount }}
            </el-descriptions-item>
            <el-descriptions-item label="高 / 中 / 低相似">
              {{ agentTask.output.similarResearchAnalysis.highSimilarityCount }}
              / {{ agentTask.output.similarResearchAnalysis.moderateSimilarityCount }}
              / {{ agentTask.output.similarResearchAnalysis.lowSimilarityCount }}
            </el-descriptions-item>
            <el-descriptions-item label="排除未通过核验引文">
              {{ agentTask.output.similarResearchAnalysis.excludedCitationCount }}
            </el-descriptions-item>
            <el-descriptions-item label="算法版本">
              {{ agentTask.output.similarResearchAnalysis.algorithmVersion }}
            </el-descriptions-item>
            <el-descriptions-item
              label="输入 SHA-256"
              :span="4"
            >
              <code>{{ agentTask.output.similarResearchAnalysis.inputSha256 }}</code>
            </el-descriptions-item>
          </el-descriptions>
          <el-table
            :data="agentTask.output.similarResearchAnalysis.similarResearch"
            size="small"
            class="file-result"
            empty-text="当前核验结果中没有可分析来源"
          >
            <el-table-column
              prop="sourceType"
              label="来源类型"
              width="170"
            />
            <el-table-column
              prop="sourceIdentifier"
              label="来源标识"
              width="145"
            />
            <el-table-column
              prop="title"
              label="题名"
              min-width="280"
            />
            <el-table-column
              label="相似度"
              width="150"
            >
              <template #default="{ row }">
                <el-tag
                  :type="row.similarityTier === 'HIGH'
                    ? 'danger'
                    : row.similarityTier === 'MODERATE'
                      ? 'warning'
                      : 'info'"
                >
                  {{ row.similarityScore }} / {{ row.similarityTier }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="verificationStatus"
              label="核验状态"
              min-width="210"
            />
            <el-table-column
              label="已匹配维度"
              min-width="260"
            >
              <template #default="{ row }">
                {{
                  row.dimensions
                    .filter((dimension: { matched: boolean }) => dimension.matched)
                    .map((dimension: { dimension: string }) => dimension.dimension)
                    .join(' / ') || '无'
                }}
              </template>
            </el-table-column>
          </el-table>
          <el-divider content-position="left">
            潜在研究空白（待专家确认）
          </el-divider>
          <el-table
            :data="agentTask.output.similarResearchAnalysis.potentialResearchGaps"
            size="small"
            class="file-result"
            empty-text="当前维度匹配未产生空白建议"
          >
            <el-table-column
              prop="code"
              label="空白编码"
              min-width="220"
            />
            <el-table-column
              prop="statement"
              label="建议"
              min-width="360"
            />
            <el-table-column
              prop="basis"
              label="形成依据与边界"
              min-width="360"
            />
          </el-table>
          <el-alert
            v-for="limitation in agentTask.output.similarResearchAnalysis.limitations"
            :key="limitation"
            :title="limitation"
            type="info"
            :closable="false"
            class="strategy-limit"
          />
        </template>
        <el-alert
          v-if="agentTask?.status === 'FAILED'"
          :title="`${agentTask.errorCode}：${agentTask.errorMessage}`"
          type="error"
          :closable="false"
        />
      </template>
      <section v-permission="['HOSPITAL_ADMIN', 'AUDIT_ADMIN']">
        <el-divider content-position="left">
          操作审计
        </el-divider>
        <el-button @click="refreshAudits">
          刷新审计记录
        </el-button>
        <el-table
          :data="audits"
          size="small"
          empty-text="尚未加载审计记录"
        >
          <el-table-column
            prop="occurredAt"
            label="时间"
            width="210"
          />
          <el-table-column
            prop="action"
            label="动作"
          />
          <el-table-column
            prop="resourceType"
            label="资源"
          />
          <el-table-column
            prop="resourceId"
            label="资源 ID"
          />
        </el-table>
      </section>
    </template>
  </el-card>
</template>
