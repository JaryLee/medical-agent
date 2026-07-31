package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.PrototypeService;
import com.jarylee.medicalagent.agent.ResearchOutputValidator;
import com.jarylee.medicalagent.agent.mock.MockModelRouter;
import com.jarylee.medicalagent.agent.mock.MockResearchModel;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.audit.MemoryAuditRepository;
import com.jarylee.medicalagent.auth.*;
import com.jarylee.medicalagent.document.ControlledDocxService;
import com.jarylee.medicalagent.infrastructure.PlatformStore;
import com.jarylee.medicalagent.literature.MockPubMedGateway;
import com.jarylee.medicalagent.literature.MockPubMedSearchGateway;
import com.jarylee.medicalagent.literature.MemoryLiteratureSearchRepository;
import com.jarylee.medicalagent.literature.LiteratureSearchService;
import com.jarylee.medicalagent.literature.SearchStrategyService;
import com.jarylee.medicalagent.literature.ClinicalTrialsQueryService;
import com.jarylee.medicalagent.literature.ClinicalTrialsSearchService;
import com.jarylee.medicalagent.literature.MemoryClinicalTrialSearchRepository;
import com.jarylee.medicalagent.literature.MockClinicalTrialsSearchGateway;
import com.jarylee.medicalagent.literature.MockCrossrefMetadataGateway;
import com.jarylee.medicalagent.literature.MemoryLiteratureValidationRepository;
import com.jarylee.medicalagent.literature.LiteratureValidationService;
import com.jarylee.medicalagent.literature.MemorySimilarResearchAnalysisRepository;
import com.jarylee.medicalagent.literature.SimilarResearchAnalysisService;
import com.jarylee.medicalagent.file.MemoryObjectStorage;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry;
import com.jarylee.medicalagent.research.MemoryProjectMemberRepository;
import com.jarylee.medicalagent.research.MemoryProjectRepository;
import com.jarylee.medicalagent.research.ResearchProjectService;
import com.jarylee.medicalagent.review.ExpertReviewService;
import com.jarylee.medicalagent.review.MemoryExpertReviewRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWorkflowTest {
    private static final String IDEA = "我想研究2型糖尿病患者使用SGLT2抑制剂后肾功能的变化";
    private final PlatformStore store = new PlatformStore();
    private final MutableCurrentUser currentUser = new MutableCurrentUser();
    private final AuditService audit = new AuditService(new MemoryAuditRepository(store));
    private final ResearchProjectService projects = new ResearchProjectService(
            new MemoryProjectRepository(store), new MemoryProjectMemberRepository(store),
            new MemoryIdentityRepository(store), currentUser, audit);
    private final MemoryAgentWorkflowRepository repository = new MemoryAgentWorkflowRepository();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-27T02:00:00Z"), ZoneOffset.UTC);
    private final MemoryModelCallAuditRepository modelCallRepository =
            new MemoryModelCallAuditRepository();
    private final ModelCallAuditService modelCalls =
            new ModelCallAuditService(
                    modelCallRepository, json, clock,
                    new com.jarylee.medicalagent.safety.ExternalModelInputGuard(
                            new com.jarylee.medicalagent.safety.SensitiveContentPolicy(),
                            new com.jarylee.medicalagent.safety.PromptInjectionPolicy()));
    private final SearchStrategyService searchStrategies = new SearchStrategyService();
    private final LiteratureSearchService literatureSearch = new LiteratureSearchService(
            new MockPubMedSearchGateway(json), new MemoryLiteratureSearchRepository(),
            new MemoryObjectStorage(), json, clock, 20);
    private final ClinicalTrialsSearchService clinicalTrialsSearch =
            new ClinicalTrialsSearchService(
                    new MockClinicalTrialsSearchGateway(json),
                    new MemoryClinicalTrialSearchRepository(),
                    new ClinicalTrialsQueryService(), new MemoryObjectStorage(),
                    json, clock, 20);
    private final LiteratureValidationService literatureValidation =
            new LiteratureValidationService(
                    new MockCrossrefMetadataGateway(json),
                    new MemoryLiteratureValidationRepository(),
                    new MemoryObjectStorage(), json, clock);
    private final SimilarResearchAnalysisService similarResearchAnalysis =
            new SimilarResearchAnalysisService(
                    new MemorySimilarResearchAnalysisRepository(), json, clock);
    private final ObservationalStudyRuleService studyRules =
            new ObservationalStudyRuleService(new ObservationalStudyRuleRegistry(json));
    private final ObservationalDesignRecommendationService observationalDesign =
            new ObservationalDesignRecommendationService(
                    studyRules,
                    new MemoryObservationalDesignRecommendationRepository(),
                    json,
                    clock);
    private final ResearchProtocolGenerationService protocolGeneration =
            new ResearchProtocolGenerationService(
                    new MemoryResearchProtocolRepository(), json, clock);
    private final StatisticalAnalysisDraftService statisticalAnalysis =
            new StatisticalAnalysisDraftService(
                    new MemoryStatisticalAnalysisDraftRepository(), json, clock);
    private final ClaimCitationValidationService claimCitationValidation =
            new ClaimCitationValidationService(
                    new MemoryClaimCitationValidationRepository(), json, clock);
    private final StrobeCompletenessService strobeCompleteness =
            new StrobeCompletenessService(
                    new MemoryStrobeCompletenessRepository(),
                    new StrobeChecklistRegistry(), json, clock);
    private final AgentEventStream eventStream = new AgentEventStream();
    private final ExpertReviewService expertReview = new ExpertReviewService(
            new MemoryExpertReviewRepository(), repository, projects, currentUser,
            audit, eventStream, json, clock);
    private final AgentWorkflowService service = new AgentWorkflowService(
            repository, currentUser, projects, audit, json, clock,
            Duration.ofMinutes(15), eventStream, searchStrategies,
            observationalDesign);
    private final PrototypeService prototype = new PrototypeService(
            new MockModelRouter(new MockResearchModel()), new MockPubMedGateway(),
            new ControlledDocxService(), new ResearchOutputValidator(), new PromptTemplateRegistry());
    private final AgentWorkflowWorker worker = new AgentWorkflowWorker(
            repository, service, prototype, json, clock, Duration.ofSeconds(45),
            Duration.ofSeconds(10),
            new PromptTemplateRegistry(), modelCalls,
            new AgentToolCallService(
                    new MemoryAgentToolCallRepository(), json, clock),
            studyRules, searchStrategies,
            literatureSearch, clinicalTrialsSearch, literatureValidation,
            similarResearchAnalysis, observationalDesign, protocolGeneration,
            statisticalAnalysis, claimCitationValidation, strobeCompleteness,
            expertReview);

    @Test
    void persistsMultiStepWorkflowAndReplaysEventsAcrossHumanConfirmation() {
        UUID hospitalId = UUID.randomUUID();
        currentUser.user = new AuthenticatedUser(
                UUID.randomUUID(), hospitalId, "doctor", Set.of(Role.DOCTOR), false);
        var project = projects.create("AGENT-001", "Agent课题", "agent-project");

        var created = service.create(project.id(), IDEA, "task-001");
        var duplicate = service.create(project.id(), IDEA, "task-001");
        assertThat(duplicate.id()).isEqualTo(created.id());

        worker.poll();
        var clarification = service.get(created.id());
        assertThat(clarification.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(clarification.currentStep()).isEqualTo("STEP_03_ASK_CLARIFICATION");
        assertThat(clarification.output().has("directions")).isFalse();
        service.submitClarifications(created.id(), answers(clarification));
        worker.poll();
        var waiting = service.get(created.id());
        assertThat(waiting.currentStep()).isEqualTo("STEP_05_CONFIRM_DIRECTION");
        assertThat(waiting.output().at("/directions")).hasSize(3);
        var modelAudit = modelCallRepository.findByTask(hospitalId, created.id());
        assertThat(modelAudit)
                .extracting(ModelCallAuditRepository.ModelCallData::stepCode)
                .containsExactly("STEP_01_PARSE_IDEA", "STEP_04_GENERATE_RESEARCH_DIRECTIONS");
        assertThat(modelAudit)
                .allSatisfy(call -> {
                    assertThat(call.status()).isEqualTo("SUCCEEDED");
                    assertThat(call.inputSha256()).matches("[0-9a-f]{64}");
                    assertThat(call.outputSha256()).matches("[0-9a-f]{64}");
                    assertThat(call.inputSnapshotJson()).doesNotContain(IDEA);
                    assertThat(call.inputSnapshotJson())
                            .contains("promptTemplate", "replaySources");
                    assertThat(call.outputSnapshotJson())
                            .contains("controlledOutput", "durationMs");
                });

        long lastBeforeConfirmation = repository
                .findEventsAfter(hospitalId, created.id(), 0).getLast().id();
        assertThatThrownBy(() -> service.confirm(
                created.id(), "DIR-02",
                UUID.fromString(waiting.output().path("candidateSetId").asText()),
                "0".repeat(64)))
                .hasMessageContaining("候选集已变化");
        service.confirm(
                created.id(), "DIR-02",
                UUID.fromString(waiting.output().path("candidateSetId").asText()),
                waiting.output().path("candidateSetHash").asText());
        worker.poll();

        var strategyWaiting = service.get(created.id());
        assertThat(strategyWaiting.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(strategyWaiting.currentStep()).isEqualTo("STEP_07_BUILD_SEARCH_STRATEGY");
        assertThat(strategyWaiting.output().at("/searchStrategy/schemaVersion").asText())
                .isEqualTo("search-strategy/v1");
        String generatedQuery = strategyWaiting.output()
                .at("/searchStrategy/pubmedQuery").asText();
        assertThat(generatedQuery).contains("[Title/Abstract]", "[MeSH Terms]");

        var searchQueued = service.confirmSearchStrategy(
                created.id(), generatedQuery + "\nNOT animals[MeSH Terms]");
        assertThat(searchQueued.status()).isEqualTo("QUEUED");
        assertThat(searchQueued.currentStep()).isEqualTo("STEP_08_SEARCH_PUBMED");
        worker.poll();

        var clinicalTrialsQueued = service.get(created.id());
        assertThat(clinicalTrialsQueued.status()).isEqualTo("QUEUED");
        assertThat(clinicalTrialsQueued.currentStep())
                .isEqualTo("STEP_09_SEARCH_CLINICAL_TRIALS");
        assertThat(clinicalTrialsQueued.output().at("/pubmedSearch/records")).hasSize(2);
        worker.poll();

        var validationQueued = service.get(created.id());
        assertThat(validationQueued.status()).isEqualTo("QUEUED");
        assertThat(validationQueued.currentStep())
                .isEqualTo("STEP_10_VALIDATE_LITERATURE");
        worker.poll();

        var similarResearchQueued = service.get(created.id());
        assertThat(similarResearchQueued.status()).isEqualTo("QUEUED");
        assertThat(similarResearchQueued.currentStep())
                .isEqualTo("STEP_11_ANALYZE_SIMILAR_RESEARCH");
        worker.poll();

        var designQueued = service.get(created.id());
        assertThat(designQueued.status()).isEqualTo("QUEUED");
        assertThat(designQueued.currentStep())
                .isEqualTo("STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN");
        worker.poll();

        var designWaiting = service.get(created.id());
        assertThat(designWaiting.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(designWaiting.output()
                .at("/observationalDesignRecommendation/alternatives")).hasSize(3);
        assertThat(designWaiting.output()
                .at("/observationalDesignRecommendation/confirmationStatus").asText())
                .isEqualTo("PENDING_CONFIRMATION");
        var recommendedType = com.jarylee.medicalagent.agent.model.ResearchModels.StudyType
                .valueOf(designWaiting.output()
                        .at("/observationalDesignRecommendation/recommendedStudyType").asText());
        String primaryOutcome = designWaiting.output()
                .at("/observationalDesignRecommendation/primaryOutcomeCandidate").asText();
        var protocolQueued = service.confirmObservationalDesign(
                created.id(), recommendedType, primaryOutcome, true);
        assertThat(protocolQueued.status()).isEqualTo("QUEUED");
        assertThat(protocolQueued.currentStep())
                .isEqualTo("STEP_13_GENERATE_PROTOCOL_SECTIONS");
        worker.poll();
        var statisticalQueued = service.get(created.id());
        assertThat(statisticalQueued.status()).isEqualTo("QUEUED");
        assertThat(statisticalQueued.currentStep())
                .isEqualTo("STEP_14_GENERATE_STATISTICAL_DRAFT");
        assertThat(statisticalQueued.output().at("/protocolDraft/sections"))
                .hasSize(18);
        worker.poll();
        var claimValidationQueued = service.get(created.id());
        assertThat(claimValidationQueued.status()).isEqualTo("QUEUED");
        assertThat(claimValidationQueued.currentStep())
                .isEqualTo("STEP_15_VALIDATE_CLAIMS_AND_CITATIONS");
        assertThat(claimValidationQueued.output()
                .at("/statisticalAnalysisDraft/schemaVersion").asText())
                .isEqualTo("statistical-analysis-draft/v1");
        worker.poll();
        var strobeQueued = service.get(created.id());
        assertThat(strobeQueued.status()).isEqualTo("QUEUED");
        assertThat(strobeQueued.currentStep())
                .isEqualTo("STEP_16_CHECK_STROBE_COMPLETENESS");
        assertThat(strobeQueued.output()
                .at("/claimCitationValidation/schemaVersion").asText())
                .isEqualTo("claim-citation-validation-result/v1");
        worker.poll();
        var completed = service.get(created.id());
        assertThat(completed.status())
                .withFailMessage("STEP09失败：%s / %s",
                        completed.errorCode(), completed.errorMessage())
                .isEqualTo("WAITING_CONFIRMATION");
        assertThat(completed.currentStep())
                .isEqualTo("STEP_17_WAIT_EXPERT_REVIEW");
        assertThat(completed.output().at("/peco/schemaVersion").asText()).isEqualTo("peco/v1");
        assertThat(completed.output().at("/designAssessment/readyForDraft").asBoolean()).isTrue();
        assertThat(completed.output().at("/searchStrategy/confirmationStatus").asText())
                .isEqualTo("CONFIRMED");
        assertThat(completed.output().at("/searchStrategy/pubmedQuery").asText())
                .endsWith("NOT animals[MeSH Terms]");
        assertThat(completed.output().at("/pubmedSearch/schemaVersion").asText())
                .isEqualTo("pubmed-search-result/v1");
        assertThat(completed.output().at("/pubmedSearch/records")).hasSize(2);
        assertThat(completed.output().at("/pubmedSearch/rawResponseSha256").asText())
                .matches("[0-9a-f]{64}");
        assertThat(completed.output().at("/clinicalTrialsSearch/schemaVersion").asText())
                .isEqualTo("clinicaltrials-search-result/v1");
        assertThat(completed.output().at("/clinicalTrialsSearch/records")).hasSize(2);
        assertThat(completed.output().at("/clinicalTrialsSearch/records/0/nctId").asText())
                .matches("NCT\\d{8}");
        assertThat(completed.output().at("/literatureValidation/schemaVersion").asText())
                .isEqualTo("literature-validation-result/v1");
        assertThat(completed.output().at("/literatureValidation/citations")).hasSize(2);
        assertThat(completed.output().at("/literatureValidation/evidenceLinks")).hasSize(2);
        assertThat(completed.output().at("/similarResearchAnalysis/schemaVersion").asText())
                .isEqualTo("similar-research-analysis-result/v1");
        assertThat(completed.output().at("/similarResearchAnalysis/similarResearch"))
                .hasSize(4);
        assertThat(completed.output().at("/similarResearchAnalysis/conclusion").asText())
                .contains("不代表完成了全部数据库和灰色文献检索");
        assertThat(completed.output()
                .at("/observationalDesignRecommendation/confirmationStatus").asText())
                .isEqualTo("CONFIRMED");
        assertThat(completed.output()
                .at("/observationalDesignRecommendation/protocolGenerationAuthorized")
                .asBoolean()).isTrue();
        assertThat(completed.output().at("/protocolDraft/schemaVersion").asText())
                .isEqualTo("research-protocol-draft/v1");
        assertThat(completed.output().at("/protocolDraft/sections")).hasSize(18);
        assertThat(completed.output()
                .at("/protocolDraft/sections/12/content").asText())
                .contains("MISSING_NEEDS_INPUT");
        assertThat(completed.output()
                .at("/protocolDraft/sections/12/versionNo").asInt()).isEqualTo(2);
        assertThat(completed.output()
                .at("/statisticalAnalysisDraft/schemaVersion").asText())
                .isEqualTo("statistical-analysis-draft/v1");
        assertThat(completed.output()
                .at("/statisticalAnalysisDraft/sampleSizeParameters"))
                .hasSize(8);
        assertThat(completed.output()
                .at("/statisticalAnalysisDraft/sampleSizeParameters/0/valueStatus")
                .asText()).isEqualTo("MISSING_NEEDS_INPUT");
        assertThat(completed.output()
                .at("/statisticalAnalysisDraft/sampleSizeParameters/0/value").isNull())
                .isTrue();
        assertThat(completed.output()
                .at("/claimCitationValidation/schemaVersion").asText())
                .isEqualTo("claim-citation-validation-result/v1");
        assertThat(completed.output()
                .at("/claimCitationValidation/claims").size()).isPositive();
        assertThat(completed.output()
                .at("/claimCitationValidation/claims/0/expertConfirmationStatus")
                .asText()).isEqualTo("PENDING_REVIEW");
        assertThat(completed.output()
                .at("/claimCitationValidation/claims").toString())
                .doesNotContain(
                        "\"supportStatus\":\"SUPPORTED\"",
                        "\"evidenceScope\":\"FULL_TEXT\"");
        assertThat(completed.output()
                .at("/strobeCompletenessCheck/schemaVersion").asText())
                .isEqualTo("strobe-completeness-check-result/v1");
        assertThat(completed.output()
                .at("/strobeCompletenessCheck/totalItemCount").asInt())
                .isEqualTo(22);
        assertThat(completed.output()
                .at("/strobeCompletenessCheck/items")).hasSize(22);
        assertThat(completed.output()
                .at("/strobeCompletenessCheck").toString())
                .doesNotContain("\"score\"");
        assertThat(completed.output()
                .at("/expertReview/status").asText())
                .isEqualTo("WAITING_EXPERT_REVIEW");
        assertThat(repository.findEventsAfter(hospitalId, created.id(), lastBeforeConfirmation))
                .extracting(AgentWorkflowRepository.EventData::eventType)
                .contains("DIRECTION_CONFIRMED", "WAITING_SEARCH_STRATEGY",
                        "SEARCH_STRATEGY_CONFIRMED", "LITERATURE_SEARCH_COMPLETED",
                        "CLINICAL_TRIALS_SEARCH_COMPLETED",
                        "LITERATURE_VALIDATION_COMPLETED",
                        "SIMILAR_RESEARCH_ANALYSIS_COMPLETED",
                        "WAITING_OBSERVATIONAL_DESIGN_CONFIRMATION",
                        "OBSERVATIONAL_DESIGN_CONFIRMED",
                        "PROTOCOL_GENERATION_QUEUED",
                        "PROTOCOL_SECTIONS_GENERATED",
                        "STATISTICAL_DRAFT_GENERATED",
                        "CLAIMS_AND_CITATIONS_VALIDATED",
                        "STROBE_COMPLETENESS_CHECKED", "EXPERT_REVIEW_REQUIRED");
    }

    @Test
    void reclaimsExpiredLeaseAfterWorkerRestartAndHonorsCancelAndRetryStateRules() {
        UUID hospitalId = UUID.randomUUID();
        currentUser.user = new AuthenticatedUser(
                UUID.randomUUID(), hospitalId, "doctor", Set.of(Role.DOCTOR), false);
        var project = projects.create("AGENT-002", "恢复课题", "agent-recovery-project");
        var created = service.create(project.id(), IDEA, "task-recovery");
        var task = repository.findById(hospitalId, created.id()).orElseThrow();

        assertThat(repository.claim(
                hospitalId, task.id(), task.version(), clock.instant().minusSeconds(1))).isTrue();
        worker.poll();
        assertThat(service.get(task.id()).currentStep()).isEqualTo("STEP_03_ASK_CLARIFICATION");

        var cancelledSource = service.create(project.id(), IDEA, "task-cancel");
        assertThat(service.cancel(cancelledSource.id()).status()).isEqualTo("CANCELLED");
        worker.poll();
        assertThat(service.get(cancelledSource.id()).status()).isEqualTo("CANCELLED");

        var retrySource = service.create(project.id(), IDEA, "task-retry");
        repository.fail(hospitalId, retrySource.id(), "TEST_FAILURE", "test");
        assertThat(service.retry(retrySource.id()).status()).isEqualTo("QUEUED");
    }

    @Test
    void preservesClarificationRoundsAndRegeneratesDirectionsAfterRevision() {
        UUID hospitalId = UUID.randomUUID();
        currentUser.user = new AuthenticatedUser(
                UUID.randomUUID(), hospitalId, "doctor", Set.of(Role.DOCTOR), false);
        var project = projects.create("AGENT-003", "多轮澄清课题", "agent-round-project");
        var created = service.create(project.id(), IDEA, "task-rounds");

        worker.poll();
        var firstWaiting = service.get(created.id());
        service.submitClarifications(created.id(), answers(firstWaiting));
        worker.poll();

        var directions = service.get(created.id());
        assertThat(directions.currentStep()).isEqualTo("STEP_05_CONFIRM_DIRECTION");
        Map<String, String> revised = new LinkedHashMap<>(answers(directions));
        String revisedQuestion = revised.keySet().iterator().next();
        revised.put(revisedQuestion, "修订后的第二轮答案");

        var queued = service.submitClarifications(created.id(), revised);
        assertThat(queued.currentStep()).isEqualTo("STEP_04_GENERATE_RESEARCH_DIRECTIONS");
        assertThat(queued.status()).isEqualTo("QUEUED");
        assertThat(queued.input().at("/clarificationAnswers").get(revisedQuestion).asText())
                .isEqualTo("修订后的第二轮答案");

        var history = service.clarificationHistory(created.id());
        assertThat(history).extracting(AgentWorkflowService.ClarificationRoundView::roundNo)
                .containsExactly(1, 2);
        assertThat(history.getFirst().sourceStep()).isEqualTo("STEP_03_ASK_CLARIFICATION");
        assertThat(history.getLast().sourceStep()).isEqualTo("STEP_05_CONFIRM_DIRECTION");
        assertThat(history.getLast().answers().get(revisedQuestion).asText())
                .isEqualTo("修订后的第二轮答案");

        worker.poll();
        var regenerated = service.get(created.id());
        assertThat(regenerated.currentStep()).isEqualTo("STEP_05_CONFIRM_DIRECTION");
        assertThat(regenerated.output().at("/directions")).hasSize(3);
    }

    private Map<String, String> answers(AgentWorkflowService.TaskView task) {
        return java.util.stream.StreamSupport
                .stream(task.output().at("/clarificationQuestions").spliterator(), false)
                .map(com.fasterxml.jackson.databind.JsonNode::asText)
                .collect(Collectors.toMap(
                        question -> question,
                        question -> {
                            if (question.contains("门诊")) return "来自门诊电子病历数据库";
                            if (question.contains("暴露和对照")) return "按首次处方日期分组，并设同类药物对照";
                            if (question.contains("主要结局")) return "主要结局为12个月eGFR绝对变化";
                            return "观察12个月，可获得年龄、性别、基线eGFR和合并用药";
                        }));
    }

    private static class MutableCurrentUser implements CurrentUserProvider {
        private AuthenticatedUser user;
        @Override public AuthenticatedUser requireUser() { return user; }
    }
}
