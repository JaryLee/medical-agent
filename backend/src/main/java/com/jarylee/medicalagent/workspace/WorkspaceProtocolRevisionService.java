package com.jarylee.medicalagent.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.workflow.AgentEventStream;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import com.jarylee.medicalagent.workflow.ResearchProtocolModels;
import com.jarylee.medicalagent.workflow.ResearchProtocolRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceProtocolRevisionService {
    private final WorkspaceReadModelService readModels;
    private final WorkspaceArtifactReadService artifacts;
    private final ResearchProtocolRepository protocols;
    private final AgentWorkflowRepository workflows;
    private final AgentEventStream events;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration taskTimeout;

    public WorkspaceProtocolRevisionService(
            WorkspaceReadModelService readModels,
            WorkspaceArtifactReadService artifacts,
            ResearchProtocolRepository protocols,
            AgentWorkflowRepository workflows,
            AgentEventStream events,
            AuditService audit,
            ObjectMapper json,
            Clock clock,
            @Value("${medical.agent.task-timeout:15m}") Duration taskTimeout) {
        this.readModels = readModels;
        this.artifacts = artifacts;
        this.protocols = protocols;
        this.workflows = workflows;
        this.events = events;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.taskTimeout = taskTimeout;
    }

    @Transactional
    public void updateSection(
            WorkspaceReadModelService.WorkspaceContext context,
            String sectionKey,
            int expectedVersionNo,
            String content,
            String changeReason) {
        reviseSection(
                context, sectionKey, expectedVersionNo,
                normalizeContent(content),
                "HUMAN",
                normalizeReason(changeReason, "HUMAN_REVISION"));
    }

    @Transactional
    public void regenerateSection(
            WorkspaceReadModelService.WorkspaceContext context,
            String sectionKey,
            int expectedVersionNo,
            String changeReason) {
        var source = source(context, sectionKey);
        String initialContent = protocols.findSectionVersions(
                        context.actor().hospitalId(), source.sectionId()).stream()
                .findFirst()
                .map(ResearchProtocolRepository.SectionVersionData::content)
                .orElseThrow(() -> new IllegalStateException(
                        "方案章节缺少初始版本"));
        reviseSection(
                context, sectionKey, expectedVersionNo,
                initialContent,
                "AGENT_DETERMINISTIC",
                normalizeReason(
                        changeReason,
                        "DETERMINISTIC_INITIAL_REGENERATION"));
    }

    @Transactional
    public int applyModelCandidate(
            WorkspaceReadModelService.WorkspaceContext context,
            String sectionKey,
            int expectedVersionNo,
            String content) {
        return reviseSection(
                context,
                sectionKey,
                expectedVersionNo,
                normalizeContent(content),
                "AGENT_MODEL",
                "MODEL_CANDIDATE_APPLIED").versionNo();
    }

    @Transactional
    public void submitRevision(
            WorkspaceReadModelService.WorkspaceContext context,
            String workspaceIdempotencyKey) {
        if (!context.canEdit()) {
            throw BusinessException.forbidden(
                    "当前账号没有修订研究方案的权限");
        }
        AgentWorkflowRepository.TaskData previous =
                requireRevisionTask(context);
        ResearchProtocolRepository.ProtocolData previousProtocol =
                protocols.findByAgentTask(
                                context.actor().hospitalId(), previous.id())
                        .orElseThrow(() -> new IllegalStateException(
                                "当前任务缺少研究方案"));
        ObjectNode previousOutput = output(previous);
        ResearchProtocolModels.ProtocolDraft currentDraft = read(
                previousOutput.path("protocolDraft").toString(),
                ResearchProtocolModels.ProtocolDraft.class);
        boolean changedInCurrentReviewRound = protocols.findSections(
                        context.actor().hospitalId(),
                        previousProtocol.id()).stream()
                .anyMatch(section -> section.currentVersionNo() > 1);
        if (!changedInCurrentReviewRound) {
            throw BusinessException.conflict(
                    "PROJECT_ACTION_NOT_ALLOWED",
                    "至少完成一处实质性方案修订后才能重新提交");
        }

        Instant now = clock.instant();
        UUID newTaskId = UUID.randomUUID();
        UUID newProtocolId = UUID.randomUUID();
        List<ResearchProtocolModels.ProtocolSection> newSections =
                currentDraft.sections().stream()
                        .map(section -> new ResearchProtocolModels.ProtocolSection(
                                UUID.randomUUID(),
                                section.sectionCode(),
                                section.title(),
                                section.sortOrder(),
                                1,
                                section.content(),
                                section.contentFormat(),
                                section.origin(),
                                section.evidenceStatus(),
                                section.sourceIdentifiers(),
                                section.issuesToConfirm()))
                        .toList();
        ResearchProtocolModels.ProtocolDraft newDraft =
                new ResearchProtocolModels.ProtocolDraft(
                        currentDraft.schemaVersion(),
                        newProtocolId,
                        now,
                        currentDraft.studyType(),
                        currentDraft.title(),
                        newSections,
                        currentDraft.issuesToConfirm(),
                        sha256(writeBytes(newSections)),
                        currentDraft.generatorVersion(),
                        currentDraft.limitations());

        ObjectNode newOutput = previousOutput.deepCopy();
        for (String field : List.of(
                "statisticalAnalysisDraft",
                "claimCitationValidation",
                "strobeCompletenessCheck",
                "expertReview",
                "documentExport")) {
            newOutput.remove(field);
        }
        newOutput.set("protocolDraft", json.valueToTree(newDraft));
        String outputJson = write(newOutput);
        AgentWorkflowRepository.TaskData newTask =
                new AgentWorkflowRepository.TaskData(
                        newTaskId,
                        context.actor().hospitalId(),
                        context.project().id(),
                        context.actor().userId(),
                        "STEP_14_GENERATE_STATISTICAL_DRAFT",
                        "QUEUED",
                        previous.inputJson(),
                        outputJson,
                        null,
                        now.plus(taskTimeout),
                        false,
                        0,
                        null,
                        null,
                        now,
                        now,
                        null);
        workflows.create(
                newTask,
                "v2-revision-" + sha256(
                        workspaceIdempotencyKey
                                .getBytes(StandardCharsets.UTF_8)));
        protocols.save(
                new ResearchProtocolRepository.ProtocolData(
                        newProtocolId,
                        context.actor().hospitalId(),
                        context.project().id(),
                        newTaskId,
                        "DRAFT",
                        newDraft.studyType().name(),
                        newDraft.title(),
                        newDraft.schemaVersion(),
                        newDraft.generatorVersion(),
                        newDraft.inputSha256(),
                        write(newDraft.issuesToConfirm()),
                        write(newDraft),
                        now),
                newSections);
        var created = workflows.appendEvent(
                context.actor().hospitalId(),
                newTaskId,
                "PROTOCOL_REVISION_SUBMITTED",
                "STEP_14_GENERATE_STATISTICAL_DRAFT",
                write(new RevisionPayload(
                        "STATISTICS_AND_QUALITY_RECHECK_REQUIRED")),
                now);
        publishAfterCommit(created);
        audit.record(
                context.actor(),
                "WORKSPACE_PROTOCOL_REVISION_SUBMITTED",
                "RESEARCH_PROJECT",
                context.project().projectKey());
    }

    private ResearchProtocolRepository.SectionVersionData reviseSection(
            WorkspaceReadModelService.WorkspaceContext context,
            String sectionKey,
            int expectedVersionNo,
            String content,
            String origin,
            String changeReason) {
        if (!context.canEdit()) {
            throw BusinessException.forbidden(
                    "当前账号没有修订研究方案的权限");
        }
        if (expectedVersionNo < 1) {
            throw new IllegalArgumentException(
                    "expectedSectionVersion 必须大于 0");
        }
        RevisionSource source = source(context, sectionKey);
        Instant now = clock.instant();
        var appended = protocols.appendSectionVersion(
                        context.actor().hospitalId(),
                        source.protocol().id(),
                        source.sectionId(),
                        expectedVersionNo,
                        content,
                        origin,
                        changeReason,
                        context.actor().userId(),
                        now)
                .orElseThrow(() -> BusinessException.conflict(
                        "PROTOCOL_SECTION_VERSION_CONFLICT",
                        "方案章节版本或锁定状态已变化，请刷新后重试"));

        ObjectNode output = output(source.task());
        boolean updatedSection = false;
        for (var node : output.path("protocolDraft").path("sections")) {
            if (source.sectionId().toString()
                    .equals(node.path("sectionId").asText())) {
                ObjectNode section = (ObjectNode) node;
                section.put("versionNo", appended.versionNo());
                section.put("content", appended.content());
                section.put("origin", appended.origin());
                updatedSection = true;
                break;
            }
        }
        if (!updatedSection) {
            throw new IllegalStateException(
                    "当前任务方案快照缺少目标章节");
        }
        String outputJson = write(output);
        if (!workflows.updateProtocolRevisionOutput(
                context.actor().hospitalId(),
                source.task().id(),
                source.task().version(),
                outputJson,
                now)) {
            throw BusinessException.conflict(
                    "READ_MODEL_VERSION_CONFLICT",
                    "课题状态已变化，请刷新后重试");
        }
        if (!protocols.updateResultSnapshot(
                context.actor().hospitalId(),
                source.protocol().id(),
                output.path("protocolDraft").toString(),
                now)) {
            throw new IllegalStateException(
                    "研究方案当前快照无法更新");
        }
        var event = workflows.appendEvent(
                context.actor().hospitalId(),
                source.task().id(),
                "PROTOCOL_SECTION_REVISED",
                "STEP_17_WAIT_EXPERT_REVIEW",
                write(new SectionRevisionPayload(
                        source.sectionCode(),
                        appended.versionNo(),
                        origin)),
                now);
        publishAfterCommit(event);
        audit.record(
                context.actor(),
                "WORKSPACE_PROTOCOL_SECTION_REVISED",
                "RESEARCH_PROJECT",
                context.project().projectKey());
        return appended;
    }

    private RevisionSource source(
            WorkspaceReadModelService.WorkspaceContext context,
            String sectionKey) {
        AgentWorkflowRepository.TaskData task =
                requireRevisionTask(context);
        ResearchProtocolRepository.ProtocolData protocol =
                protocols.findByAgentTask(
                                context.actor().hospitalId(), task.id())
                        .orElseThrow(() -> new IllegalStateException(
                                "当前任务缺少研究方案"));
        UUID sectionId = UUID.fromString(
                artifacts.resolveSectionId(
                        context.project().projectKey(),
                        task,
                        sectionKey));
        String sectionCode = protocols.findSections(
                        context.actor().hospitalId(), protocol.id()).stream()
                .filter(section -> section.id().equals(sectionId))
                .findFirst()
                .map(ResearchProtocolRepository.SectionData::sectionCode)
                .orElseThrow(() -> BusinessException.conflict(
                        "PROJECT_ACTION_NOT_ALLOWED",
                        "方案章节已变化，请刷新后重试"));
        return new RevisionSource(
                task, protocol, sectionId, sectionCode);
    }

    private AgentWorkflowRepository.TaskData requireRevisionTask(
            WorkspaceReadModelService.WorkspaceContext context) {
        AgentWorkflowRepository.TaskData task =
                readModels.latestTask(context);
        if (task == null
                || !"STEP_17_WAIT_EXPERT_REVIEW".equals(
                task.currentStep())
                || !"REVISION_REQUIRED".equals(task.status())) {
            throw BusinessException.conflict(
                    "PROJECT_ACTION_NOT_ALLOWED",
                    "只有审核退回后的当前方案可以修订");
        }
        return task;
    }

    private String normalizeContent(String value) {
        if (value == null || value.strip().isBlank()) {
            throw new IllegalArgumentException(
                    "方案章节内容不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > 30000) {
            throw new IllegalArgumentException(
                    "方案章节内容不能超过 30000 字");
        }
        return normalized;
    }

    private String normalizeReason(
            String value, String fallback) {
        String normalized = value == null
                ? "" : value.strip();
        if (normalized.isBlank()) normalized = fallback;
        if (normalized.length() > 80) {
            throw new IllegalArgumentException(
                    "变更原因不能超过 80 字");
        }
        return normalized;
    }

    private ObjectNode output(
            AgentWorkflowRepository.TaskData task) {
        try {
            return (ObjectNode) json.readTree(task.outputJson());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Agent 任务方案快照损坏", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "研究方案快照损坏", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "研究方案修订序列化失败", exception);
        }
    }

    private byte[] writeBytes(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "研究方案修订哈希序列化失败", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "无法计算研究方案修订哈希", exception);
        }
    }

    private void publishAfterCommit(
            AgentWorkflowRepository.EventData event) {
        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            TransactionSynchronizationManager
                    .registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    events.publish(event);
                                }
                            });
        } else {
            events.publish(event);
        }
    }

    private record RevisionSource(
            AgentWorkflowRepository.TaskData task,
            ResearchProtocolRepository.ProtocolData protocol,
            UUID sectionId,
            String sectionCode) {}

    private record SectionRevisionPayload(
            String sectionCode,
            int versionNo,
            String origin) {}

    private record RevisionPayload(
            String downstreamValidation) {}
}
