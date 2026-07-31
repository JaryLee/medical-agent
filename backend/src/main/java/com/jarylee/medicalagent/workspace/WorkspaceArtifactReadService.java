package com.jarylee.medicalagent.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.document.CitationStyleModels;
import com.jarylee.medicalagent.document.CitationStyleService;
import com.jarylee.medicalagent.document.DocumentExportModels;
import com.jarylee.medicalagent.document.DocumentExportService;
import com.jarylee.medicalagent.document.DocumentTemplateService;
import com.jarylee.medicalagent.review.ExpertReviewModels;
import com.jarylee.medicalagent.review.ExpertReviewService;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import com.jarylee.medicalagent.workflow.ResearchProtocolRepository;
import com.jarylee.medicalagent.workspace.WorkspaceArtifactModels.ArtifactDownload;
import com.jarylee.medicalagent.workspace.WorkspaceArtifactModels.ArtifactSectionView;
import com.jarylee.medicalagent.workspace.WorkspaceModels.Envelope;
import com.jarylee.medicalagent.workspace.WorkspaceModels.LabeledCode;
import com.jarylee.medicalagent.workspace.WorkspaceModels.ResponseMeta;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.UUID;

@Service
public class WorkspaceArtifactReadService {
    private static final String DRAFT_DISCLAIMER =
            "仅供科研设计讨论，未经伦理和科研管理审批。"
                    + "本页内容不构成诊疗建议、正式研究批准或统计结论。";
    private static final String EVIDENCE_DISCLAIMER =
            "仅供科研设计讨论，未经伦理和科研管理审批。"
                    + "自动检索和元数据核验存在数据库与摘要覆盖限制；"
                    + "相似度和潜在空白不构成创新性证明。";
    private static final String QUALITY_DISCLAIMER =
            "仅供科研设计讨论，未经伦理和科研管理审批。"
                    + "主张—引用和 STROBE 结果是自动预检查，"
                    + "不能替代医学、统计或科研管理专家审核。";

    private final WorkspaceReadModelService readModels;
    private final ExpertReviewService reviews;
    private final ResearchProtocolRepository protocols;
    private final DocumentTemplateService templates;
    private final CitationStyleService citationStyles;
    private final DocumentExportService exports;
    private final ObjectMapper json;
    private final Clock clock;

    public WorkspaceArtifactReadService(
            WorkspaceReadModelService readModels,
            ExpertReviewService reviews,
            ResearchProtocolRepository protocols,
            DocumentTemplateService templates,
            CitationStyleService citationStyles,
            DocumentExportService exports,
            ObjectMapper json,
            Clock clock) {
        this.readModels = readModels;
        this.reviews = reviews;
        this.protocols = protocols;
        this.templates = templates;
        this.citationStyles = citationStyles;
        this.exports = exports;
        this.json = json;
        this.clock = clock;
    }

    public Envelope<ArtifactSectionView> evidence(String projectKey) {
        return section(projectKey, "EVIDENCE", "医学证据",
                this::evidenceContent, EVIDENCE_DISCLAIMER);
    }

    public Envelope<ArtifactSectionView> design(String projectKey) {
        return section(projectKey, "RESEARCH_DESIGN", "研究设计",
                this::designContent, DRAFT_DISCLAIMER);
    }

    public Envelope<ArtifactSectionView> protocol(String projectKey) {
        return section(projectKey, "PROTOCOL", "研究方案",
                this::protocolContent, DRAFT_DISCLAIMER);
    }

    public Envelope<ArtifactSectionView> statistics(String projectKey) {
        return section(projectKey, "STATISTICS", "统计分析",
                this::statisticsContent, DRAFT_DISCLAIMER);
    }

    public Envelope<ArtifactSectionView> quality(String projectKey) {
        return section(projectKey, "QUALITY", "质量检查",
                this::qualityContent, QUALITY_DISCLAIMER);
    }

    public Envelope<ArtifactSectionView> internalReview(String projectKey) {
        var context = readModels.resolve(projectKey);
        var aggregate = readModels.aggregate(context);
        return envelope(aggregate, new ArtifactSectionView(
                projectKey,
                "INTERNAL_REVIEW",
                "内部审核",
                sectionStatus(aggregate, "INTERNAL_REVIEW"),
                reviewContent(projectKey, aggregate.task()),
                actionsFor(aggregate, "review"),
                "内部审核不代表伦理审批、机构正式批准或临床使用许可。"));
    }

    public Envelope<ArtifactSectionView> draftExport(String projectKey) {
        var context = readModels.resolve(projectKey);
        var aggregate = readModels.aggregate(context);
        return envelope(aggregate, new ArtifactSectionView(
                projectKey,
                "DRAFT_EXPORT",
                "科研草案导出",
                sectionStatus(aggregate, "DRAFT_EXPORT"),
                exportContent(projectKey, aggregate.task()),
                actionsFor(aggregate, "export"),
                "仅可导出已完成内部三方确认的科研草案；正式方案导出在系统中保持禁用。"));
    }

    public ArtifactDownload download(String projectKey, String exportKey) {
        var context = readModels.resolve(projectKey);
        AgentWorkflowRepository.TaskData task = readModels.latestTask(context);
        if (task == null) throw BusinessException.notFound("科研草案不存在");
        DocumentExportModels.ExportView value;
        try {
            value = exports.getByTask(task.id());
        } catch (BusinessException exception) {
            throw BusinessException.notFound("科研草案不存在");
        }
        String expected = opaque("exp_", projectKey, value.id().toString());
        if (!expected.equals(exportKey)) {
            throw BusinessException.notFound("科研草案不存在");
        }
        var file = exports.download(value.id());
        return new ArtifactDownload(
                file.fileName(), file.contentType(),
                file.content(), file.sha256());
    }

    String resolveSectionId(
            String projectKey,
            AgentWorkflowRepository.TaskData task,
            String sectionKey) {
        JsonNode output = readTree(task.outputJson());
        if (output != null) {
            for (JsonNode section : output.path("protocolDraft").path("sections")) {
                String rawId = section.path("sectionId").asText();
                String expected = opaque(
                        "sec_", projectKey,
                        section.path("sectionCode").asText());
                if (expected.equals(sectionKey)) return rawId;
            }
        }
        throw BusinessException.conflict(
                "PROJECT_ACTION_NOT_ALLOWED",
                "方案章节已变化，请刷新后重试");
    }

    String resolveCheckItemId(
            String projectKey,
            AgentWorkflowRepository.TaskData task,
            String checkItemKey) {
        JsonNode output = readTree(task.outputJson());
        if (output != null) {
            for (JsonNode item : output.path(
                    "strobeCompletenessCheck").path("items")) {
                String rawId = item.path("itemResultId").asText();
                if (opaque("chk_", projectKey, rawId).equals(checkItemKey)) {
                    return rawId;
                }
            }
        }
        throw BusinessException.conflict(
                "PROJECT_ACTION_NOT_ALLOWED",
                "质量检查项已变化，请刷新后重试");
    }

    String resolveTemplateId(String projectKey, String templateKey) {
        for (DocumentExportModels.TemplateView template : templates.list()) {
            if ("PUBLISHED".equals(template.status())
                    && opaque("tpl_", projectKey, template.id().toString())
                    .equals(templateKey)) {
                return template.id().toString();
            }
        }
        throw BusinessException.conflict(
                "PROJECT_ACTION_NOT_ALLOWED",
                "文档模板已变化，请刷新后重试");
    }

    String resolveStyleId(String projectKey, String styleKey) {
        for (CitationStyleModels.StyleView style : citationStyles.list()) {
            if ("PUBLISHED".equals(style.status())
                    && opaque("style_", projectKey, style.id().toString())
                    .equals(styleKey)) {
                return style.id().toString();
            }
        }
        throw BusinessException.conflict(
                "PROJECT_ACTION_NOT_ALLOWED",
                "引用格式已变化，请刷新后重试");
    }

    private Envelope<ArtifactSectionView> section(
            String projectKey,
            String sectionCode,
            String title,
            Function<ArtifactSource, Map<String, Object>> contentBuilder,
            String disclaimer) {
        var context = readModels.resolve(projectKey);
        var aggregate = readModels.aggregate(context);
        JsonNode output = aggregate.task() == null
                ? null : readTree(aggregate.task().outputJson());
        ArtifactSectionView value = new ArtifactSectionView(
                projectKey,
                sectionCode,
                title,
                sectionStatus(aggregate, sectionCode),
                contentBuilder.apply(
                        new ArtifactSource(
                                projectKey,
                                context.actor().hospitalId(),
                                context.project().id(),
                                output)),
                actionsFor(aggregate, routeSegment(sectionCode)),
                disclaimer);
        return envelope(aggregate, value);
    }

    private Envelope<ArtifactSectionView> envelope(
            WorkspaceReadModelService.Aggregate aggregate,
            ArtifactSectionView value) {
        return new Envelope<>(
                value,
                new ResponseMeta(
                        aggregate.cursor().readModelVersion(),
                        clock.instant(),
                        aggregate.cursor().latestEventId()));
    }

    private LabeledCode sectionStatus(
            WorkspaceReadModelService.Aggregate aggregate,
            String sectionCode) {
        return aggregate.stages().stream()
                .filter(stage -> sectionCode.equals(stage.code()))
                .findFirst()
                .map(stage -> new LabeledCode(
                        stage.status(), stage.summary()))
                .orElse(new LabeledCode(
                        "NOT_STARTED", "尚未开始"));
    }

    private List<WorkspaceModels.AllowedAction> actionsFor(
            WorkspaceReadModelService.Aggregate aggregate,
            String routeSegment) {
        return aggregate.summary().allowedActions().stream()
                .filter(action -> action.targetRoute() != null
                        && action.targetRoute().endsWith(
                        "/" + routeSegment))
                .toList();
    }

    private Map<String, Object> evidenceContent(ArtifactSource source) {
        Map<String, Object> content = new LinkedHashMap<>();
        if (source.output() == null) return immutable(content);

        JsonNode strategy = source.output().path("searchStrategy");
        if (strategy.isObject()) {
            Map<String, Object> value = scalars(strategy,
                    "schemaVersion", "queryVersion",
                    "confirmationStatus", "originalResearchQuestion",
                    "generatedPubmedQuery", "pubmedQuery");
            value.put("databases", strings(
                    strategy.path("databases")));
            value.put("filters", strings(
                    strategy.path("filters")));
            value.put("limitations", strings(
                    strategy.path("limitations")));
            value.put("concepts", maps(
                    strategy.path("concepts"), node -> {
                        Map<String, Object> item = scalars(
                                node, "code", "label", "required");
                        item.put("terms", strings(
                                node.path("terms")));
                        return item;
                    }));
            content.put("searchStrategy", immutable(value));
        }

        JsonNode pubmed = source.output().path("pubmedSearch");
        if (pubmed.isObject()) {
            Map<String, Object> value = scalars(pubmed,
                    "schemaVersion", "database", "query",
                    "queryVersion", "searchedAt",
                    "totalResultCount", "returnedCount",
                    "toolVersion");
            value.put("records", maps(
                    pubmed.path("records"), node -> {
                        Map<String, Object> item = scalars(node,
                                "pmid", "doi", "title", "journal",
                                "publicationDate", "evidenceScope",
                                "verified", "source");
                        item.put("authors", strings(
                                node.path("authors")));
                        return item;
                    }));
            value.put("limitations", strings(
                    pubmed.path("limitations")));
            content.put("pubmed", immutable(value));
        }

        JsonNode trials = source.output().path(
                "clinicalTrialsSearch");
        if (trials.isObject()) {
            Map<String, Object> value = scalars(trials,
                    "schemaVersion", "database", "sourceType",
                    "query", "queryVersion", "searchedAt",
                    "totalResultCount", "returnedCount",
                    "toolVersion", "dataVersion", "cacheHit");
            value.put("records", maps(
                    trials.path("records"), node -> {
                        Map<String, Object> item = scalars(node,
                                "nctId", "briefTitle",
                                "officialTitle", "overallStatus",
                                "studyType", "briefSummary",
                                "leadSponsor", "startDate",
                                "completionDate", "enrollment",
                                "hasResults", "evidenceScope",
                                "verified", "source");
                        for (String field : List.of(
                                "phases", "conditions",
                                "interventions",
                                "primaryOutcomes", "countries",
                                "linkedPmids")) {
                            item.put(field, strings(
                                    node.path(field)));
                        }
                        return item;
                    }));
            value.put("limitations", strings(
                    trials.path("limitations")));
            content.put("clinicalTrials", immutable(value));
        }

        JsonNode validation = source.output().path(
                "literatureValidation");
        if (validation.isObject()) {
            Map<String, Object> value = scalars(validation,
                    "schemaVersion", "validatedAt",
                    "totalCount", "verifiedCount",
                    "metadataDifferenceCount", "mismatchCount",
                    "crossrefNotFoundCount",
                    "doiNotAvailableCount", "toolVersion");
            value.put("citations", maps(
                    validation.path("citations"), node -> {
                        Map<String, Object> item = scalars(node,
                                "pmid", "doi", "status",
                                "validationSource", "message");
                        item.put("fieldChecks", maps(
                                node.path("fieldChecks"), check ->
                                        scalars(check,
                                                "field", "status",
                                                "pubmedValue",
                                                "crossrefValue")));
                        return item;
                    }));
            value.put("evidenceLinks", maps(
                    validation.path("evidenceLinks"),
                    node -> scalars(node, "nctId", "pmid",
                            "relationship", "status")));
            value.put("limitations", strings(
                    validation.path("limitations")));
            content.put("validation", immutable(value));
        }

        JsonNode similar = source.output().path(
                "similarResearchAnalysis");
        if (similar.isObject()) {
            Map<String, Object> value = scalars(similar,
                    "schemaVersion", "analyzedAt",
                    "researchQuestion", "analyzedSourceCount",
                    "excludedCitationCount",
                    "highSimilarityCount",
                    "moderateSimilarityCount",
                    "lowSimilarityCount", "conclusion",
                    "algorithmVersion");
            value.put("databaseScope", strings(
                    similar.path("databaseScope")));
            value.put("similarResearch", maps(
                    similar.path("similarResearch"), node -> {
                        Map<String, Object> item = scalars(node,
                                "sourceType", "sourceIdentifier",
                                "pmid", "doi", "nctId", "title",
                                "publicationOrCompletionDate",
                                "similarityScore", "similarityTier",
                                "verificationStatus",
                                "evidenceScope");
                        item.put("differences", strings(
                                node.path("differences")));
                        return item;
                    }));
            value.put("potentialResearchGaps", maps(
                    similar.path("potentialResearchGaps"),
                    node -> {
                        Map<String, Object> item = scalars(
                                node, "code", "statement", "basis");
                        item.put("basisSourceIdentifiers",
                                strings(node.path(
                                        "basisSourceIdentifiers")));
                        return item;
                    }));
            value.put("limitations", strings(
                    similar.path("limitations")));
            content.put("similarResearch", immutable(value));
        }
        return immutable(content);
    }

    private Map<String, Object> designContent(
            ArtifactSource source) {
        if (source.output() == null) return Map.of();
        JsonNode node = source.output().path(
                "observationalDesignRecommendation");
        if (!node.isObject()) return Map.of();
        Map<String, Object> value = scalars(node,
                "schemaVersion", "recommendedAt",
                "recommendedStudyType",
                "primaryOutcomeCandidate",
                "readyForProtocolDraft",
                "confirmationStatus", "confirmedStudyType",
                "confirmedPrimaryOutcome",
                "protocolGenerationAuthorized",
                "confirmedAt", "algorithmVersion");
        value.put("alternatives", maps(
                node.path("alternatives"), alternative -> {
                    Map<String, Object> item = scalars(alternative,
                            "rank", "studyType", "score",
                            "feasibilityStatus", "rationale");
                    for (String field : List.of(
                            "requiredFields", "missingFields",
                            "biasRisks",
                            "evidenceConsiderations")) {
                        item.put(field, strings(
                                alternative.path(field)));
                    }
                    return item;
                }));
        value.put("unresolvedItems", strings(
                node.path("unresolvedItems")));
        value.put("requiredConfirmations", strings(
                node.path("requiredConfirmations")));
        value.put("limitations", strings(
                node.path("limitations")));
        return Map.of("recommendation", immutable(value));
    }

    private Map<String, Object> protocolContent(
            ArtifactSource source) {
        if (source.output() == null) return Map.of();
        JsonNode node = source.output().path("protocolDraft");
        if (!node.isObject()) return Map.of();
        Map<String, Object> value = scalars(node,
                "schemaVersion", "generatedAt", "studyType",
                "title", "generatorVersion");
        value.put("sections", maps(
                node.path("sections"), section -> {
                    String rawId = section.path(
                            "sectionId").asText();
                    Map<String, Object> item = scalars(section,
                            "sectionCode", "title", "sortOrder",
                            "versionNo", "content",
                            "contentFormat", "origin",
                            "evidenceStatus");
                    item.put("sectionKey", opaque(
                            "sec_", source.projectKey(),
                            section.path("sectionCode").asText()));
                    item.put("sourceIdentifiers", strings(
                            section.path("sourceIdentifiers")));
                    item.put("issuesToConfirm", strings(
                            section.path("issuesToConfirm")));
                    List<ResearchProtocolRepository.ProjectSectionVersionData>
                            history = protocols.findProjectSectionVersions(
                            source.hospitalId(),
                            source.projectId(),
                            section.path("sectionCode").asText());
                    List<Map<String, Object>> publicHistory =
                            new ArrayList<>();
                    for (int index = 0; index < history.size(); index++) {
                        var revision = history.get(index);
                        Map<String, Object> version =
                                new LinkedHashMap<>();
                        version.put("historyKey", opaque(
                                "ver_",
                                source.projectKey(),
                                revision.sectionCode(),
                                Integer.toString(index + 1),
                                revision.createdAt().toString()));
                        version.put("revisionNo", index + 1);
                        version.put("sourceVersionNo",
                                revision.sourceVersionNo());
                        version.put("content", revision.content());
                        version.put("contentFormat",
                                revision.contentFormat());
                        version.put("origin", revision.origin());
                        version.put("evidenceStatus",
                                revision.evidenceStatus());
                        version.put("changeReason",
                                revision.changeReason());
                        version.put("createdAt",
                                revision.createdAt());
                        publicHistory.add(immutable(version));
                    }
                    item.put("versionHistory",
                            List.copyOf(publicHistory));
                    return item;
                }));
        value.put("issuesToConfirm", strings(
                node.path("issuesToConfirm")));
        value.put("limitations", strings(
                node.path("limitations")));
        return Map.of("protocol", immutable(value));
    }

    private Map<String, Object> statisticsContent(
            ArtifactSource source) {
        if (source.output() == null) return Map.of();
        JsonNode node = source.output().path(
                "statisticalAnalysisDraft");
        if (!node.isObject()) return Map.of();
        Map<String, Object> value = scalars(node,
                "schemaVersion", "generatedAt", "studyType",
                "primaryOutcome", "outcomeTypeStatus",
                "confidenceIntervalPlan", "generatorVersion");
        for (String field : List.of(
                "descriptiveAnalysis",
                "primaryAnalysisCandidates",
                "secondaryAnalysis", "covariates",
                "potentialConfounders",
                "stratifiedAnalyses", "subgroupAnalyses",
                "sensitivityAnalyses", "missingDataPlan",
                "multipleComparisonPlan", "modelDiagnostics",
                "effectMeasureCandidates",
                "recommendedSoftware", "issuesToConfirm",
                "limitations")) {
            value.put(field, strings(node.path(field)));
        }
        value.put("sampleSizeParameters", maps(
                node.path("sampleSizeParameters"),
                parameter -> scalars(parameter,
                        "code", "label", "required",
                        "valueStatus", "value", "unit",
                        "rationale")));
        return Map.of("statisticalDraft", immutable(value));
    }

    private Map<String, Object> qualityContent(
            ArtifactSource source) {
        Map<String, Object> content = new LinkedHashMap<>();
        if (source.output() == null) return immutable(content);
        JsonNode claims = source.output().path(
                "claimCitationValidation");
        if (claims.isObject()) {
            Map<String, Object> value = scalars(claims,
                    "schemaVersion", "validatedAt",
                    "claimCount", "citationLinkCount",
                    "abstractOnlyClaimCount",
                    "needsExpertReviewClaimCount",
                    "validatorVersion");
            value.put("claims", maps(
                    claims.path("claims"), claim -> {
                        Map<String, Object> item = scalars(claim,
                                "sectionCode", "claimOrder",
                                "claimType", "claimText",
                                "supportStatus",
                                "expertConfirmationStatus");
                        item.put("claimKey", opaque(
                                "clm_", source.projectKey(),
                                claim.path("claimId").asText()));
                        item.put("citationLinks", maps(
                                claim.path("citationLinks"), link -> {
                                    Map<String, Object> citation =
                                            scalars(link,
                                                    "linkOrder",
                                                    "sourceType",
                                                    "pmid", "doi",
                                                    "title",
                                                    "supportLevel",
                                                    "evidenceScope",
                                                    "evidenceExcerpt",
                                                    "excerptLocation",
                                                    "citationValidationStatus",
                                                    "manualConfirmationStatus");
                                    citation.put("citationKey",
                                            opaque("cit_",
                                                    source.projectKey(),
                                                    link.path(
                                                            "linkId")
                                                            .asText()));
                                    return citation;
                                }));
                        item.put("issuesToConfirm", strings(
                                claim.path("issuesToConfirm")));
                        return item;
                    }));
            value.put("limitations", strings(
                    claims.path("limitations")));
            content.put("claimCitation", immutable(value));
        }
        JsonNode strobe = source.output().path(
                "strobeCompletenessCheck");
        if (strobe.isObject()) {
            Map<String, Object> value = scalars(strobe,
                    "schemaVersion", "checkedAt",
                    "guidelineCode", "guidelineVersion",
                    "studyType", "totalItemCount",
                    "coveredCount", "partiallyCoveredCount",
                    "missingCount", "notApplicableCount",
                    "needsExpertReviewCount",
                    "checkerVersion", "sourceReference",
                    "automaticPrecheckDisclaimer");
            value.put("items", maps(
                    strobe.path("items"), item -> {
                        Map<String, Object> check = scalars(item,
                                "itemCode", "sectionGroup",
                                "requirementSummary", "studyType",
                                "status", "message", "suggestion",
                                "requiresExpertReview");
                        check.put("checkItemKey", opaque(
                                "chk_", source.projectKey(),
                                item.path(
                                        "itemResultId").asText()));
                        check.put("mappedSectionCodes", strings(
                                item.path("mappedSectionCodes")));
                        check.put("evidenceSnippets", strings(
                                item.path("evidenceSnippets")));
                        return check;
                    }));
            value.put("limitations", strings(
                    strobe.path("limitations")));
            content.put("strobe", immutable(value));
        }
        return immutable(content);
    }

    private Map<String, Object> reviewContent(
            String projectKey,
            AgentWorkflowRepository.TaskData task) {
        if (task == null
                || WorkspaceStageCatalog.indexForStep(
                task.currentStep())
                < WorkspaceStageCatalog.indexForStep(
                "STEP_17_WAIT_EXPERT_REVIEW")) {
            return Map.of();
        }
        ExpertReviewModels.ReviewView review;
        try {
            review = reviews.get(task.id());
        } catch (BusinessException exception) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        put(value, "status", review.status());
        put(value, "reviewRoundNo", review.reviewRoundNo());
        put(value, "version", review.version());
        put(value, "medicalDecision",
                review.expertDecision() == null
                        ? null : review.expertDecision().name());
        put(value, "medicalSummary", review.expertSummary());
        put(value, "medicalDecidedAt",
                review.expertDecidedAt());
        put(value, "statisticalDecision",
                review.statisticalDecision() == null
                        ? null
                        : review.statisticalDecision().name());
        put(value, "statisticalSummary",
                review.statisticalSummary());
        put(value, "statisticalDecidedAt",
                review.statisticalDecidedAt());
        put(value, "ownerConfirmed",
                review.ownerConfirmedAt() != null);
        put(value, "ownerConfirmedAt",
                review.ownerConfirmedAt());
        put(value, "sectionsLocked",
                review.sectionsLocked());
        JsonNode taskOutput = readTree(task.outputJson());
        List<Map<String, Object>> targets = new ArrayList<>();
        if (taskOutput != null) {
            for (JsonNode section : taskOutput.path(
                    "protocolDraft").path("sections")) {
                Map<String, Object> target =
                        new LinkedHashMap<>();
                target.put("targetType",
                        "PROTOCOL_SECTION");
                target.put("targetKey", opaque(
                        "sec_", projectKey,
                        section.path("sectionCode").asText()));
                target.put("targetVersion",
                        section.path("versionNo").asInt());
                target.put("label",
                        section.path("title").asText());
                targets.add(immutable(target));
            }
            for (JsonNode item : taskOutput.path(
                    "strobeCompletenessCheck").path("items")) {
                Map<String, Object> target =
                        new LinkedHashMap<>();
                target.put("targetType", "STROBE_ITEM");
                target.put("targetKey", opaque(
                        "chk_", projectKey,
                        item.path("itemResultId").asText()));
                target.put("label",
                        "STROBE " + item.path(
                                "itemCode").asText());
                targets.add(immutable(target));
            }
        }
        value.put("commentTargets", List.copyOf(targets));
        value.put("comments", review.comments().stream()
                .map(comment -> {
                    Map<String, Object> item =
                            new LinkedHashMap<>();
                    item.put("commentKey", opaque(
                            "cmt_", projectKey,
                            comment.id().toString()));
                    item.put("targetType",
                            comment.protocolSectionId() == null
                                    ? "STROBE_ITEM"
                                    : "PROTOCOL_SECTION");
                    if (comment.protocolSectionId() != null) {
                        item.put("targetKey", opaque(
                                "sec_", projectKey,
                                findSectionCode(
                                        task,
                                        comment.protocolSectionId()
                                                .toString())));
                        item.put("targetVersion",
                                comment.protocolSectionVersionNo());
                    } else {
                        item.put("targetKey", opaque(
                                "chk_", projectKey,
                                comment.strobeItemResultId()
                                        .toString()));
                    }
                    item.put("commentType",
                            comment.commentType().name());
                    item.put("responsibility",
                            comment.responsibility());
                    item.put("reviewRoundNo",
                            comment.reviewRoundNo());
                    item.put("content", comment.content());
                    item.put("createdAt", comment.createdAt());
                    return immutable(item);
                }).toList());
        value.put("history", review.history().stream()
                .map(action -> {
                    Map<String, Object> item =
                            new LinkedHashMap<>();
                    put(item, "actionType",
                            action.actionType());
                    put(item, "reviewRoundNo",
                            action.reviewRoundNo());
                    put(item, "summary", action.summary());
                    put(item, "occurredAt",
                            action.occurredAt());
                    return immutable(item);
                }).toList());
        return Map.of("review", immutable(value));
    }

    private Map<String, Object> exportContent(
            String projectKey,
            AgentWorkflowRepository.TaskData task) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("templates", templates.list().stream()
                .filter(template ->
                        "PUBLISHED".equals(template.status()))
                .map(template -> exportTemplate(
                        projectKey, template))
                .toList());
        value.put("citationStyles", citationStyles.list().stream()
                .filter(style ->
                        "PUBLISHED".equals(style.status()))
                .map(style -> exportStyle(projectKey, style))
                .toList());
        JsonNode output = task == null
                ? null : readTree(task.outputJson());
        if (output != null
                && output.path("documentExport").isObject()) {
            try {
                var export = exports.getByTask(task.id());
                Map<String, Object> completed =
                        new LinkedHashMap<>();
                completed.put("exportKey", opaque(
                        "exp_", projectKey,
                        export.id().toString()));
                put(completed, "status", export.status());
                put(completed, "citationStyleCode",
                        export.citationStyleCode());
                put(completed, "citationStyleVersion",
                        export.citationStyleVersion());
                put(completed, "citationCount",
                        export.citationCount());
                put(completed, "fileName",
                        export.fileName());
                put(completed, "contentType",
                        export.contentType());
                put(completed, "contentSha256",
                        export.contentSha256());
                put(completed, "contentSize",
                        export.contentSize());
                put(completed, "completedAt",
                        export.completedAt());
                completed.put("downloadUrl",
                        "/api/research/projects/"
                                + projectKey + "/exports/"
                                + completed.get("exportKey")
                                + "/download");
                value.put("completedExport",
                        immutable(completed));
            } catch (BusinessException ignored) {
                // A subsequent read-model refresh fills the committed record.
            }
        }
        return immutable(value);
    }

    private Map<String, Object> exportTemplate(
            String projectKey,
            DocumentExportModels.TemplateView template) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("templateKey", opaque(
                "tpl_", projectKey, template.id().toString()));
        item.put("templateCode", template.templateCode());
        item.put("templateName", template.templateName());
        item.put("versionNo", template.versionNo());
        item.put("placeholderSchemaVersion",
                template.placeholderSchemaVersion());
        return immutable(item);
    }

    private Map<String, Object> exportStyle(
            String projectKey,
            CitationStyleModels.StyleView style) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("styleKey", opaque(
                "style_", projectKey, style.id().toString()));
        item.put("styleCode", style.styleCode());
        item.put("styleName", style.styleName());
        item.put("versionNo", style.versionNo());
        item.put("layout", style.layout());
        return immutable(item);
    }

    private String findSectionCode(
            AgentWorkflowRepository.TaskData task,
            String rawSectionId) {
        JsonNode output = readTree(task.outputJson());
        if (output != null) {
            for (JsonNode section : output.path(
                    "protocolDraft").path("sections")) {
                if (rawSectionId.equals(
                        section.path("sectionId").asText())) {
                    return section.path(
                            "sectionCode").asText();
                }
            }
        }
        throw new IllegalStateException(
                "审核批注引用了当前方案快照之外的章节");
    }

    private String routeSegment(String sectionCode) {
        return switch (sectionCode) {
            case "EVIDENCE" -> "evidence";
            case "RESEARCH_DESIGN" -> "design";
            case "PROTOCOL" -> "protocol";
            case "STATISTICS" -> "statistics";
            case "QUALITY" -> "quality";
            case "INTERNAL_REVIEW" -> "review";
            case "DRAFT_EXPORT" -> "export";
            default -> "overview";
        };
    }

    private Map<String, Object> scalars(
            JsonNode source, String... fields) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (source == null || !source.isObject()) return value;
        for (String field : fields) {
            JsonNode node = source.get(field);
            if (node == null || node.isNull()
                    || node.isContainerNode()) continue;
            if (node.isBoolean()) {
                value.put(field, node.booleanValue());
            } else if (node.isIntegralNumber()) {
                value.put(field, node.longValue());
            } else if (node.isFloatingPointNumber()) {
                value.put(field, node.doubleValue());
            } else {
                value.put(field, node.asText());
            }
        }
        return value;
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(value -> {
            if (value.isValueNode()
                    && !value.asText().isBlank()) {
                result.add(value.asText());
            }
        });
        return List.copyOf(result);
    }

    private List<Map<String, Object>> maps(
            JsonNode node,
            Function<JsonNode, Map<String, Object>> mapper) {
        if (node == null || !node.isArray()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        node.forEach(value ->
                result.add(immutable(mapper.apply(value))));
        return List.copyOf(result);
    }

    private void put(
            Map<String, Object> target,
            String key,
            Object value) {
        if (value != null) target.put(key, value);
    }

    private Map<String, Object> immutable(
            Map<String, Object> source) {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(source));
    }

    private JsonNode readTree(String value) {
        if (value == null) return null;
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "课题 artifact 事实源数据损坏", exception);
        }
    }

    private String opaque(
            String prefix, String... values) {
        return WorkspaceOpaqueKey.of(prefix, values);
    }

    private record ArtifactSource(
            String projectKey,
            UUID hospitalId,
            UUID projectId,
            JsonNode output) {}
}
