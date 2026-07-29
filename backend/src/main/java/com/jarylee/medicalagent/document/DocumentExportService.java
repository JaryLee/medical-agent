package com.jarylee.medicalagent.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.auth.IdentityRepository;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.document.DocumentExportModels.ExportView;
import com.jarylee.medicalagent.file.ObjectStorage;
import com.jarylee.medicalagent.research.ResearchProjectService;
import com.jarylee.medicalagent.review.ExpertReviewRepository;
import com.jarylee.medicalagent.workflow.AgentEventStream;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentExportService {
    public static final String EXPORT_SCHEMA_VERSION = "document-export/v2";
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocumentExportRepository exports;
    private final DocumentTemplateService templates;
    private final CitationStyleService citationStyles;
    private final ControlledDocxTemplateEngine engine;
    private final AgentWorkflowRepository workflows;
    private final ExpertReviewRepository reviews;
    private final ResearchProjectService projects;
    private final IdentityRepository identities;
    private final ObjectStorage storage;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final AgentEventStream events;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final Clock clock;

    public DocumentExportService(
            DocumentExportRepository exports,
            DocumentTemplateService templates,
            CitationStyleService citationStyles,
            ControlledDocxTemplateEngine engine,
            AgentWorkflowRepository workflows,
            ExpertReviewRepository reviews,
            ResearchProjectService projects,
            IdentityRepository identities,
            ObjectStorage storage,
            CurrentUserProvider currentUser,
            AuditService audit,
            AgentEventStream events,
            com.fasterxml.jackson.databind.ObjectMapper json,
            Clock clock) {
        this.exports = exports;
        this.templates = templates;
        this.citationStyles = citationStyles;
        this.engine = engine;
        this.workflows = workflows;
        this.reviews = reviews;
        this.projects = projects;
        this.identities = identities;
        this.storage = storage;
        this.currentUser = currentUser;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    public ExportView getByTask(UUID agentTaskId) {
        AuthenticatedUser actor = requireReadyHospitalUser();
        var task = requireTask(actor, agentTaskId);
        projects.get(task.projectId());
        return view(exports.findExportByAgentTask(actor.hospitalId(), agentTaskId)
                .orElseThrow(() -> BusinessException.notFound("文档导出记录不存在")));
    }

    @Transactional
    public ExportView confirmAndExport(
            UUID agentTaskId, UUID templateVersionId,
            UUID citationStyleVersionId,
            boolean confirmReviewedContent) {
        if (!confirmReviewedContent) {
            throw new IllegalArgumentException("必须确认导出内容来自已审核锁定版本");
        }
        AuthenticatedUser actor = requireReadyHospitalUser();
        var task = requireTask(actor, agentTaskId);
        var project = projects.requireOwner(task.projectId());
        var existing = exports.findExportByAgentTask(
                actor.hospitalId(), agentTaskId);
        if (existing.isPresent()) return view(existing.get());
        if (!"STEP_18_EXPORT_DOCUMENT".equals(task.currentStep())
                || !"WAITING_CONFIRMATION".equals(task.status())) {
            throw BusinessException.conflict("Agent 任务当前不在 STEP18 导出确认状态");
        }
        var review = reviews.findByAgentTask(actor.hospitalId(), agentTaskId)
                .orElseThrow(() -> BusinessException.notFound("专家审核任务不存在"));
        if (!"APPROVED".equals(review.status()) || !review.sectionsLocked()) {
            throw BusinessException.conflict("只有专家和课题负责人已确认的锁定方案可以导出");
        }
        var template = templates.requirePublished(
                actor.hospitalId(), templateVersionId);
        var citationStyle = citationStyles.requirePublished(
                actor.hospitalId(), citationStyleVersionId);
        JsonNode output = readTree(task.outputJson());
        JsonNode protocol = output.path("protocolDraft");
        if (!review.protocolId().toString()
                .equals(protocol.path("protocolId").asText())) {
            throw new IllegalStateException("审核记录与当前方案快照不一致");
        }
        CitationSnapshot citations = validateAndBuildCitations(
                output, protocol, citationStyle);
        Map<String, String> values = values(
                task, project.name(), protocol, citations.references());
        byte[] templateContent = templates.content(actor.hospitalId(), template);
        byte[] document = engine.render(templateContent, values);
        String contentSha256 = sha256(document);
        Instant now = clock.instant();
        UUID exportId = UUID.randomUUID();
        String fileName = safeFileName(project.code() + "-" + project.name())
                + "-研究方案.docx";
        String objectKey = "hospital/" + actor.hospitalId()
                + "/projects/" + task.projectId() + "/exports/"
                + exportId + "/" + fileName;
        storage.put(objectKey, document, DOCX_CONTENT_TYPE);
        try {
            ObjectNode exportNode = json.createObjectNode();
            exportNode.put("schemaVersion", EXPORT_SCHEMA_VERSION);
            exportNode.put("exportId", exportId.toString());
            exportNode.put("templateVersionId", template.id().toString());
            exportNode.put("templateCode", template.templateCode());
            exportNode.put("templateVersionNo", template.versionNo());
            exportNode.put(
                    "citationStyleVersionId", citationStyle.id().toString());
            exportNode.put("citationStyleCode", citationStyle.styleCode());
            exportNode.put("citationStyleVersion",
                    citationStyles.versionLabel(citationStyle));
            exportNode.put("citationLayout", citationStyle.layout());
            exportNode.put("citationCount", citations.count());
            exportNode.put("contentSha256", contentSha256);
            exportNode.put("contentSize", document.length);
            exportNode.put("fileName", fileName);
            exportNode.put("completedAt", now.toString());
            ObjectNode finalOutput = output.deepCopy();
            finalOutput.set("documentExport", exportNode);
            String finalOutputJson = write(finalOutput);
            var created = exports.createExport(
                    new DocumentExportRepository.ExportData(
                            exportId, actor.hospitalId(), task.projectId(),
                            agentTaskId, review.protocolId(), review.id(),
                            template.id(), citationStyle.id(),
                            citationStyle.styleCode(),
                            citationStyles.versionLabel(citationStyle),
                            "COMPLETED",
                            actor.userId(), now, sha256(writeBytes(protocol)),
                            citations.sha256(), citations.count(), objectKey,
                            fileName, DOCX_CONTENT_TYPE, contentSha256,
                            document.length, now, null, null, now, 0));
            if (!workflows.completeExport(
                    actor.hospitalId(), agentTaskId, finalOutputJson,
                    actor.userId(), now)) {
                throw BusinessException.conflict("Agent 任务导出状态已变化");
            }
            audit.record(actor, "DOCUMENT_EXPORTED",
                    "DOCUMENT_EXPORT_RECORD", created.id().toString());
            var completedTask = workflows.findById(
                    actor.hospitalId(), agentTaskId).orElse(task);
            var event = workflows.appendEvent(
                    actor.hospitalId(), agentTaskId, "DOCUMENT_EXPORT_COMPLETED",
                    "STEP_18_EXPORT_DOCUMENT", write(exportNode), now);
            events.publish(event);
            var taskEvent = workflows.appendEvent(
                    actor.hospitalId(), completedTask.id(), "TASK_COMPLETED",
                    "STEP_18_EXPORT_DOCUMENT", write(exportNode), now);
            events.publish(taskEvent);
            return view(created);
        } catch (RuntimeException exception) {
            storage.delete(objectKey);
            throw exception;
        }
    }

    public DownloadFile download(UUID exportId) {
        AuthenticatedUser actor = requireReadyHospitalUser();
        var record = exports.findExportById(actor.hospitalId(), exportId)
                .orElseThrow(() -> BusinessException.notFound("文档导出记录不存在"));
        projects.get(record.projectId());
        if (!"COMPLETED".equals(record.status())
                || record.objectKey() == null
                || !record.objectKey().startsWith(
                "hospital/" + actor.hospitalId() + "/projects/")) {
            throw BusinessException.notFound("可下载文档不存在");
        }
        byte[] content = storage.get(record.objectKey());
        if (!record.contentSha256().equals(sha256(content))) {
            throw new IllegalStateException("导出文件哈希校验失败");
        }
        audit.record(actor, "DOCUMENT_EXPORT_DOWNLOADED",
                "DOCUMENT_EXPORT_RECORD", exportId.toString());
        return new DownloadFile(
                record.fileName(), record.contentType(), content,
                record.contentSha256());
    }

    private CitationSnapshot validateAndBuildCitations(
            JsonNode output,
            JsonNode protocol,
            CitationStyleRepository.StyleData citationStyle) {
        Set<String> referencedPmids = new LinkedHashSet<>();
        for (JsonNode section : protocol.path("sections")) {
            if (!"REFERENCES".equals(section.path("sectionCode").asText())) continue;
            for (JsonNode source : section.path("sourceIdentifiers")) {
                String value = source.asText();
                if (value.startsWith("PMID:")) {
                    referencedPmids.add(value.substring("PMID:".length()));
                }
            }
        }
        Set<String> verifiedPmids = new LinkedHashSet<>();
        for (JsonNode citation : output.path("literatureValidation").path("citations")) {
            String status = citation.path("status").asText();
            if ("VERIFIED".equals(status)
                    || "VERIFIED_WITH_METADATA_DIFFERENCES".equals(status)) {
                verifiedPmids.add(citation.path("pmid").asText());
            }
        }
        if (!verifiedPmids.containsAll(referencedPmids)) {
            Set<String> invalid = new LinkedHashSet<>(referencedPmids);
            invalid.removeAll(verifiedPmids);
            throw new IllegalStateException(
                    "方案引用包含未核验 PMID：" + String.join("、", invalid));
        }
        Map<String, JsonNode> records = new LinkedHashMap<>();
        for (JsonNode record : output.path("pubmedSearch").path("records")) {
            records.put(record.path("pmid").asText(), record);
        }
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (String pmid : referencedPmids) {
            JsonNode record = records.get(pmid);
            if (record == null) {
                throw new IllegalStateException(
                        "方案引用 PMID 缺少对应 PubMed 记录：" + pmid);
            }
            List<String> authors = new ArrayList<>();
            for (JsonNode author : record.path("authors")) {
                if (!author.asText().isBlank()) authors.add(author.asText());
            }
            lines.add(citationStyles.format(
                    citationStyle,
                    index++,
                    new CitationStyleService.CitationInput(
                            authors,
                            record.path("title").asText(),
                            record.path("journal").asText(),
                            record.path("publicationDate").asText(),
                            pmid,
                            record.path("doi").asText())));
        }
        String references = lines.isEmpty()
                ? "当前锁定方案没有可列入的已核验 PubMed 引文；不得由模型或模板补造引用。"
                : String.join("\n", lines);
        ObjectNode snapshot = json.createObjectNode();
        snapshot.putPOJO("pmids", referencedPmids);
        snapshot.put("styleVersionId", citationStyle.id().toString());
        snapshot.put("styleCode", citationStyle.styleCode());
        snapshot.put("styleVersion", citationStyles.versionLabel(citationStyle));
        snapshot.put("layout", citationStyle.layout());
        snapshot.put("authorLimit", citationStyle.authorLimit());
        snapshot.put("includePmid", citationStyle.includePmid());
        snapshot.put("includeDoi", citationStyle.includeDoi());
        snapshot.put("includeEvidenceScope",
                citationStyle.includeEvidenceScope());
        snapshot.put("evidenceScopeLabel",
                citationStyle.evidenceScopeLabel());
        return new CitationSnapshot(
                referencedPmids.size(), references,
                sha256(writeBytes(snapshot)));
    }

    private Map<String, String> values(
            AgentWorkflowRepository.TaskData task,
            String projectName,
            JsonNode protocol,
            String references) {
        Map<String, String> sections = new LinkedHashMap<>();
        for (JsonNode section : protocol.path("sections")) {
            sections.put(
                    section.path("sectionCode").asText(),
                    section.path("content").asText());
        }
        JsonNode peco = readTree(task.outputJson()).path("peco");
        String owner = identities.findUserById(task.createdBy())
                .map(IdentityRepository.UserData::username)
                .orElse(task.createdBy().toString());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("${project.title}", text(
                protocol.path("title").asText(), projectName));
        values.put("${project.principalInvestigator}", owner);
        values.put("${project.department}", "待医院补充");
        values.put("${research.background}", sections.getOrDefault(
                "BACKGROUND", "待补充"));
        values.put("${research.question}", text(
                peco.path("researchQuestion").asText(), "待补充"));
        values.put("${research.objectives}", sections.getOrDefault(
                "OBJECTIVES", "待补充"));
        values.put("${research.studyDesign}", sections.getOrDefault(
                "STUDY_DESIGN", protocol.path("studyType").asText()));
        values.put("${research.population}", sections.getOrDefault(
                "PARTICIPANTS", peco.path("population").asText()));
        values.put("${research.inclusionCriteria}", sections.getOrDefault(
                "ELIGIBILITY", "待补充"));
        values.put("${research.exclusionCriteria}", sections.getOrDefault(
                "ELIGIBILITY", "待补充"));
        values.put("${research.outcomes}", sections.getOrDefault(
                "OUTCOMES_VARIABLES", peco.path("outcome").asText()));
        values.put("${research.variables}", sections.getOrDefault(
                "OUTCOMES_VARIABLES", "待补充"));
        values.put("${research.statisticalPlan}", sections.getOrDefault(
                "STATISTICAL_ANALYSIS", "待补充"));
        values.put("${research.ethicalConsiderations}", sections.getOrDefault(
                "ETHICS_DATA_SECURITY", "待补充"));
        values.put("${research.references}", references);
        return values;
    }

    private AgentWorkflowRepository.TaskData requireTask(
            AuthenticatedUser actor, UUID agentTaskId) {
        return workflows.findById(actor.hospitalId(), agentTaskId)
                .orElseThrow(() -> BusinessException.notFound("Agent 任务不存在"));
    }

    private AuthenticatedUser requireReadyHospitalUser() {
        AuthenticatedUser actor = currentUser.requireUser();
        if (actor.forcePasswordChange() || actor.hospitalId() == null) {
            throw BusinessException.forbidden("当前账号不能执行医院文档导出");
        }
        return actor;
    }

    private ExportView view(DocumentExportRepository.ExportData value) {
        return new ExportView(
                value.id(), value.projectId(), value.agentTaskId(),
                value.protocolId(), value.reviewTaskId(),
                value.templateVersionId(), value.citationStyleVersionId(),
                value.citationStyleCode(),
                value.citationStyleVersion(), value.status(),
                value.requestedBy(), value.confirmedAt(),
                value.protocolSnapshotSha256(),
                value.citationSnapshotSha256(), value.citationCount(),
                value.fileName(), value.contentType(), value.contentSha256(),
                value.contentSize(), value.completedAt());
    }

    private JsonNode readTree(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent 任务输出损坏", exception);
        }
    }

    private byte[] writeBytes(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new IllegalStateException("导出快照序列化失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("导出结果序列化失败", exception);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String text(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private String safeFileName(String value) {
        String safe = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .strip();
        if (safe.length() > 120) safe = safe.substring(0, 120);
        return safe.isBlank() ? "research-protocol" : safe;
    }

    private record CitationSnapshot(
            int count,
            String references,
            String sha256
    ) {}

    public record DownloadFile(
            String fileName,
            String contentType,
            byte[] content,
            String sha256
    ) {}
}
