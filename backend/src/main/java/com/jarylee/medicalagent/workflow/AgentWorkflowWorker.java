package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarylee.medicalagent.agent.PrototypeService;
import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.model.ResearchModels.PecoDefinition;
import com.jarylee.medicalagent.agent.model.LogicalModelType;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry;
import com.jarylee.medicalagent.literature.SearchStrategyService;
import com.jarylee.medicalagent.literature.ClinicalTrialsGovSearchGateway.ClinicalTrialsSearchException;
import com.jarylee.medicalagent.literature.ClinicalTrialsSearchService;
import com.jarylee.medicalagent.literature.ClinicalTrialsSearchModels;
import com.jarylee.medicalagent.literature.CrossrefRestMetadataGateway.CrossrefMetadataException;
import com.jarylee.medicalagent.literature.LiteratureSearchService;
import com.jarylee.medicalagent.literature.LiteratureValidationService;
import com.jarylee.medicalagent.literature.NcbiPubMedSearchGateway.PubMedSearchException;
import com.jarylee.medicalagent.literature.PubMedSearchModels;
import com.jarylee.medicalagent.literature.LiteratureValidationModels;
import com.jarylee.medicalagent.literature.SimilarResearchAnalysisService;
import com.jarylee.medicalagent.literature.SimilarResearchAnalysisService.SimilarResearchAnalysisException;
import com.jarylee.medicalagent.literature.SimilarResearchAnalysisModels;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchStrategy;
import com.jarylee.medicalagent.review.ExpertReviewService;
import com.jarylee.medicalagent.review.ExpertReviewModels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class AgentWorkflowWorker {
    private final AgentWorkflowRepository repository;
    private final AgentWorkflowService service;
    private final PrototypeService prototype;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;
    private final PromptTemplateRegistry prompts;
    private final ModelCallAuditService modelCalls;
    private final AgentToolCallService toolCalls;
    private final ObservationalStudyRuleService studyRules;
    private final SearchStrategyService searchStrategies;
    private final LiteratureSearchService literatureSearch;
    private final ClinicalTrialsSearchService clinicalTrialsSearch;
    private final LiteratureValidationService literatureValidation;
    private final SimilarResearchAnalysisService similarResearchAnalysis;
    private final ObservationalDesignRecommendationService observationalDesign;
    private final ResearchProtocolGenerationService protocolGeneration;
    private final StatisticalAnalysisDraftService statisticalAnalysis;
    private final ClaimCitationValidationService claimCitationValidation;
    private final StrobeCompletenessService strobeCompleteness;
    private final ExpertReviewService expertReview;
    private final String workerId = "worker-" + UUID.randomUUID();
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "agent-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public AgentWorkflowWorker(AgentWorkflowRepository repository, AgentWorkflowService service,
                               PrototypeService prototype, ObjectMapper json, Clock clock,
                               @Value("${medical.agent.lease-duration:45s}") Duration leaseDuration,
                               @Value("${medical.agent.heartbeat-interval:10s}")
                               Duration heartbeatInterval,
                               PromptTemplateRegistry prompts,
                               ModelCallAuditService modelCalls,
                               AgentToolCallService toolCalls,
                               ObservationalStudyRuleService studyRules,
                               SearchStrategyService searchStrategies,
                               LiteratureSearchService literatureSearch,
                               ClinicalTrialsSearchService clinicalTrialsSearch,
                               LiteratureValidationService literatureValidation,
                               SimilarResearchAnalysisService similarResearchAnalysis,
                               ObservationalDesignRecommendationService observationalDesign,
                               ResearchProtocolGenerationService protocolGeneration,
                               StatisticalAnalysisDraftService statisticalAnalysis,
                               ClaimCitationValidationService claimCitationValidation,
                               StrobeCompletenessService strobeCompleteness,
                               ExpertReviewService expertReview) {
        this.repository = repository;
        this.service = service;
        this.prototype = prototype;
        this.json = json;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()
                || heartbeatInterval.multipliedBy(3).compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "Agent心跳间隔必须大于0且严格小于租约时长的三分之一");
        }
        this.heartbeatInterval = heartbeatInterval;
        this.prompts = prompts;
        this.modelCalls = modelCalls;
        this.toolCalls = toolCalls;
        this.studyRules = studyRules;
        this.searchStrategies = searchStrategies;
        this.literatureSearch = literatureSearch;
        this.clinicalTrialsSearch = clinicalTrialsSearch;
        this.literatureValidation = literatureValidation;
        this.similarResearchAnalysis = similarResearchAnalysis;
        this.observationalDesign = observationalDesign;
        this.protocolGeneration = protocolGeneration;
        this.statisticalAnalysis = statisticalAnalysis;
        this.claimCitationValidation = claimCitationValidation;
        this.strobeCompleteness = strobeCompleteness;
        this.expertReview = expertReview;
    }

    @Scheduled(
            fixedDelayString = "${medical.agent.worker-delay:500}",
            initialDelayString = "${medical.agent.worker-initial-delay:1000}")
    public void poll() {
        Instant now = clock.instant();
        for (var task : repository.findTimedOut(now, 20)) {
            var outcome = repository.failTimedOut(
                    task.hospitalId(), task.id(), task.version(), now,
                    new AgentWorkflowRepository.PendingEvent(
                            "timeout:v" + task.version(), "TASK_FAILED",
                            task.currentStep(),
                            write(new FailurePayload(
                                    "TASK_TIMEOUT", "Agent任务执行超时"))));
            publishApplied(outcome);
        }
        repository.claimNext(now, workerId, leaseDuration)
                .ifPresent(claimed -> {
                    service.publishCommitted(claimed.events());
                    execute(claimed.task(), claimed.claim());
                });
    }

    void execute(
            AgentWorkflowRepository.TaskData claimedFrom,
            AgentWorkflowRepository.ClaimHandle claim) {
        var task = repository.findById(claimedFrom.hospitalId(), claimedFrom.id())
                .orElseThrow();
        int attempt = claim.attemptNo();
        var heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> {
                    Instant heartbeatAt = clock.instant();
                    repository.heartbeat(
                            claim, heartbeatAt.plus(leaseDuration), heartbeatAt);
                },
                heartbeatInterval.toMillis(), heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        try {
            if (!renewClaim(claim)) return;
            AgentWorkflowService.TaskInput input =
                    json.readValue(task.inputJson(), AgentWorkflowService.TaskInput.class);
            if ("STEP_01_PARSE_IDEA".equals(task.currentStep())) {
                executeAnalysis(task, claim, input, attempt);
            } else if ("STEP_04_GENERATE_RESEARCH_DIRECTIONS".equals(task.currentStep())) {
                executeDirections(task, claim, input, attempt);
            } else if ("STEP_05_CONFIRM_DIRECTION".equals(task.currentStep())) {
                executeConfirmation(task, claim, input, attempt);
            } else if ("STEP_08_SEARCH_PUBMED".equals(task.currentStep())) {
                executePubMedSearch(task, claim, attempt);
            } else if ("STEP_09_SEARCH_CLINICAL_TRIALS".equals(task.currentStep())) {
                executeClinicalTrialsSearch(task, claim, attempt);
            } else if ("STEP_10_VALIDATE_LITERATURE".equals(task.currentStep())) {
                executeLiteratureValidation(task, claim, attempt);
            } else if ("STEP_11_ANALYZE_SIMILAR_RESEARCH".equals(task.currentStep())) {
                executeSimilarResearchAnalysis(task, claim, attempt);
            } else if ("STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN".equals(task.currentStep())) {
                executeObservationalDesignRecommendation(task, claim, input, attempt);
            } else if ("STEP_13_GENERATE_PROTOCOL_SECTIONS".equals(task.currentStep())) {
                executeProtocolSections(task, claim, attempt);
            } else if ("STEP_14_GENERATE_STATISTICAL_DRAFT".equals(task.currentStep())) {
                executeStatisticalDraft(task, claim, attempt);
            } else if ("STEP_15_VALIDATE_CLAIMS_AND_CITATIONS".equals(task.currentStep())) {
                executeClaimCitationValidation(task, claim, attempt);
            } else if ("STEP_16_CHECK_STROBE_COMPLETENESS".equals(task.currentStep())) {
                executeStrobeCompletenessCheck(task, claim, attempt);
            } else {
                throw new IllegalStateException("不支持的Agent步骤: " + task.currentStep());
            }
        } catch (PubMedSearchException exception) {
            failClaim(claim, exception.code(), exception.getMessage(), exception.getMessage());
        } catch (ClinicalTrialsSearchException exception) {
            failClaim(claim, exception.code(), exception.getMessage(), exception.getMessage());
        } catch (CrossrefMetadataException exception) {
            failClaim(claim, exception.code(), exception.getMessage(), exception.getMessage());
        } catch (SimilarResearchAnalysisException exception) {
            failClaim(claim, exception.code(), exception.getMessage(), exception.getMessage());
        } catch (Exception exception) {
            failClaim(
                    claim, "AGENT_STEP_FAILED", exception.getMessage(),
                    "Agent步骤执行失败");
        } finally {
            heartbeat.cancel(false);
        }
    }

    @PreDestroy
    void shutdownHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }

    private void executeAnalysis(AgentWorkflowRepository.TaskData task,
                                 AgentWorkflowRepository.ClaimHandle claim,
                                 AgentWorkflowService.TaskInput input, int attempt) {
        Instant started = clock.instant();
        var prompt = prompts.require("STEP_01_PARSE_IDEA");
        var route = prototype.resolve(LogicalModelType.RESEARCH_FAST);
        var invocation = modelCalls.invokeAnalysis(
                task, attempt, prompt, route, input.idea(),
                () -> prototype.invokeAnalysis(
                        LogicalModelType.RESEARCH_FAST, input.idea(), prompt));
        var analysis = invocation.output();
        if (cancelled(task)) return;
        var step01 = completedStep(task, "STEP_01_PARSE_IDEA", attempt, input,
                analysis.profile(), started, "STEP_01_PARSE_IDEA", "[]",
                invocation.modelCallId());
        var step02 = completedStep(task, "STEP_02_IDENTIFY_MISSING_INFORMATION", attempt,
                analysis.profile(), analysis.profile().missingInformation(), started, null, "[]",
                null);
        var step03 = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(), "STEP_03_ASK_CLARIFICATION",
                attempt, "WAITING_CONFIRMATION", "missing-information/v1",
                "clarification-answers/v1", write(analysis.clarificationQuestions()), null,
                null, null, "[]", null, null, clock.instant(), null, true, null, null);
        var clarification = new AgentWorkflowService.ClarificationOutput(
                analysis.profile(), analysis.clarificationQuestions(), analysis.disclaimer());
        commit(
                claim, List.of(step01, step02, step03),
                "STEP_03_ASK_CLARIFICATION", "WAITING_CONFIRMATION",
                write(clarification), null,
                List.of(
                        event(claim, "step01", "STEP_COMPLETED",
                                "STEP_01_PARSE_IDEA",
                                new StepPayload("STEP_01_PARSE_IDEA", attempt)),
                        event(claim, "step02", "STEP_COMPLETED",
                                "STEP_02_IDENTIFY_MISSING_INFORMATION",
                                new StepPayload(
                                        "STEP_02_IDENTIFY_MISSING_INFORMATION", attempt)),
                        event(claim, "waiting", "WAITING_CLARIFICATION",
                                "STEP_03_ASK_CLARIFICATION", clarification)));
    }

    private void executeDirections(AgentWorkflowRepository.TaskData task,
                                   AgentWorkflowRepository.ClaimHandle claim,
                                   AgentWorkflowService.TaskInput input, int attempt) {
        Instant started = clock.instant();
        String directionInput = contextualIdea(input);
        var prompt = prompts.require("STEP_04_GENERATE_RESEARCH_DIRECTIONS");
        var route = prototype.resolve(LogicalModelType.RESEARCH_FAST);
        var invocation = modelCalls.invokeAnalysis(
                task, attempt, prompt, route, directionInput,
                () -> prototype.invokeAnalysis(
                        LogicalModelType.RESEARCH_FAST, directionInput, prompt));
        var analysis = invocation.output();
        if (cancelled(task)) return;
        UUID candidateSetId = UUID.randomUUID();
        String candidatesJson = write(analysis.directions());
        String candidateSetHash = sha256(candidatesJson);
        var step04 = completedStep(task, "STEP_04_GENERATE_RESEARCH_DIRECTIONS", attempt,
                input, analysis.directions(), started,
                "STEP_04_GENERATE_RESEARCH_DIRECTIONS", "[]",
                invocation.modelCallId());
        var step05 = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(), "STEP_05_CONFIRM_DIRECTION",
                attempt, "WAITING_CONFIRMATION", "direction-candidates/v1",
                "direction-confirmation/v2",
                write(new AgentWorkflowService.DirectionCandidateSetPayload(
                        candidateSetId, candidateSetHash, analysis.directions())),
                null,
                null, null, "[]", null, null, clock.instant(), null, true, null, null);
        ObjectNode waitingOutput = json.valueToTree(analysis);
        waitingOutput.put("candidateSetId", candidateSetId.toString());
        waitingOutput.put("candidateSetHash", candidateSetHash);
        waitingOutput.put("candidateSetSchemaVersion", "direction-candidates/v1");
        commit(
                claim, List.of(step04, step05),
                "STEP_05_CONFIRM_DIRECTION", "WAITING_CONFIRMATION",
                write(waitingOutput), null,
                List.of(
                        event(claim, "step04", "STEP_COMPLETED",
                                "STEP_04_GENERATE_RESEARCH_DIRECTIONS",
                                new StepPayload(
                                        "STEP_04_GENERATE_RESEARCH_DIRECTIONS", attempt)),
                        event(claim, "waiting", "WAITING_CONFIRMATION",
                                "STEP_05_CONFIRM_DIRECTION", waitingOutput)));
    }

    private void executeConfirmation(AgentWorkflowRepository.TaskData task,
                                     AgentWorkflowRepository.ClaimHandle claim,
                                     AgentWorkflowService.TaskInput input, int attempt) {
        if (input.directionId() == null || input.directionId().isBlank()) {
            throw new IllegalArgumentException("缺少已确认研究方向");
        }
        if (input.directionCandidateSetId() == null
                || input.directionCandidateSetHash() == null
                || input.directionCandidateSetHash().isBlank()) {
            throw new IllegalArgumentException("缺少已确认研究方向候选集");
        }
        Instant started = clock.instant();
        ObjectNode confirmedOutput = readOutput(task.outputJson());
        if (!input.directionCandidateSetId().toString()
                .equals(confirmedOutput.path("candidateSetId").asText())
                || !input.directionCandidateSetHash()
                .equals(confirmedOutput.path("candidateSetHash").asText())) {
            throw new IllegalStateException("研究方向候选集版本或哈希不一致");
        }
        ObjectNode analysisSnapshot = confirmedOutput.deepCopy();
        analysisSnapshot.remove(List.of(
                "candidateSetId", "candidateSetHash", "candidateSetSchemaVersion"));
        var confirmedAnalysis = treeToValue(analysisSnapshot, AnalysisResult.class);
        var result = prototype.buildResearchQuestion(confirmedAnalysis, input.directionId());
        if (cancelled(task)) return;
        var step06 = completedStep(task, "STEP_06_BUILD_RESEARCH_QUESTION", attempt,
                new AgentWorkflowService.DirectionPayload(
                        input.directionId(), input.directionCandidateSetId(),
                        input.directionCandidateSetHash()),
                result.peco(), started, null, "[]", null);
        var assessment = studyRules.assess(
                result.selectedDirection().recommendedStudyType(), result.analysis(), answers(input));
        ObjectNode output = json.valueToTree(result);
        output.set("designAssessment", json.valueToTree(assessment));
        var strategy = searchStrategies.generate(result.peco());
        output.set("searchStrategy", json.valueToTree(strategy));
        var step07 = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_07_BUILD_SEARCH_STRATEGY", attempt, "WAITING_CONFIRMATION",
                "peco/v1", SearchStrategyService.SCHEMA_VERSION,
                write(result.peco()), write(strategy), null, null, "[]",
                null, null, clock.instant(), null, true, null, null);
        commit(
                claim, List.of(step06, step07),
                "STEP_07_BUILD_SEARCH_STRATEGY", "WAITING_CONFIRMATION",
                write(output), null,
                List.of(
                        event(claim, "step06", "STEP_COMPLETED",
                                "STEP_06_BUILD_RESEARCH_QUESTION",
                                new StepPayload(
                                        "STEP_06_BUILD_RESEARCH_QUESTION", attempt)),
                        event(claim, "waiting", "WAITING_SEARCH_STRATEGY",
                                "STEP_07_BUILD_SEARCH_STRATEGY", output)));
    }

    private void executePubMedSearch(
            AgentWorkflowRepository.TaskData task,
            AgentWorkflowRepository.ClaimHandle claim, int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        SearchStrategy strategy = treeToValue(output.get("searchStrategy"), SearchStrategy.class);
        var result = toolCalls.invoke(
                claim, "NCBI_EUTILS_SEARCH", strategy,
                PubMedSearchModels.SearchResult.class,
                () -> literatureSearch.execute(
                        task.hospitalId(), task.projectId(), task.id(), strategy));
        if (cancelled(task)) return;
        var step = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_08_SEARCH_PUBMED", attempt, "COMPLETED",
                SearchStrategyService.SCHEMA_VERSION,
                LiteratureSearchService.RESULT_SCHEMA_VERSION,
                write(strategy), write(result), null, null,
                write(List.of(Map.of(
                        "tool", "NCBI_EUTILS",
                        "version", result.toolVersion(),
                        "requestCount", result.externalRequestCount()))),
                null, null, started, clock.instant(), false, null, null);
        output.set("pubmedSearch", json.valueToTree(result));
        commit(
                claim, List.of(step),
                "STEP_09_SEARCH_CLINICAL_TRIALS", "QUEUED",
                write(output), null,
                List.of(event(
                        claim, "completed", "LITERATURE_SEARCH_COMPLETED",
                        "STEP_08_SEARCH_PUBMED", result)));
    }

    private void executeClinicalTrialsSearch(
            AgentWorkflowRepository.TaskData task,
            AgentWorkflowRepository.ClaimHandle claim, int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        SearchStrategy strategy = treeToValue(output.get("searchStrategy"), SearchStrategy.class);
        var result = toolCalls.invoke(
                claim, "CLINICAL_TRIALS_GOV_SEARCH", strategy,
                ClinicalTrialsSearchModels.SearchResult.class,
                () -> clinicalTrialsSearch.execute(
                        task.hospitalId(), task.projectId(), task.id(), strategy));
        if (cancelled(task)) return;
        var step = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_09_SEARCH_CLINICAL_TRIALS", attempt, "COMPLETED",
                SearchStrategyService.SCHEMA_VERSION,
                ClinicalTrialsSearchService.RESULT_SCHEMA_VERSION,
                write(strategy), write(result), null, null,
                write(List.of(Map.of(
                        "tool", "CLINICAL_TRIALS_GOV_API_V2",
                        "version", result.toolVersion(),
                        "requestCount", result.externalRequestCount(),
                        "cacheHit", result.cacheHit()))),
                null, null, started, clock.instant(), false, null, null);
        output.set("clinicalTrialsSearch", json.valueToTree(result));
        commit(
                claim, List.of(step),
                "STEP_10_VALIDATE_LITERATURE", "QUEUED",
                write(output), null,
                List.of(event(
                        claim, "completed", "CLINICAL_TRIALS_SEARCH_COMPLETED",
                        "STEP_09_SEARCH_CLINICAL_TRIALS", result)));
    }

    private void executeLiteratureValidation(
            AgentWorkflowRepository.TaskData task,
            AgentWorkflowRepository.ClaimHandle claim, int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        var pubmed = treeToValue(
                output.get("pubmedSearch"), PubMedSearchModels.SearchResult.class);
        var clinicalTrials = treeToValue(
                output.get("clinicalTrialsSearch"),
                ClinicalTrialsSearchModels.SearchResult.class);
        var validationRequest = Map.of(
                "pubmedSearch", pubmed,
                "clinicalTrialsSearch", clinicalTrials);
        var result = toolCalls.invoke(
                claim, "CROSSREF_METADATA_VALIDATION", validationRequest,
                LiteratureValidationModels.ValidationResult.class,
                () -> literatureValidation.execute(
                        task.hospitalId(), task.projectId(), task.id(),
                        pubmed, clinicalTrials));
        if (cancelled(task)) return;
        var step = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_10_VALIDATE_LITERATURE", attempt, "COMPLETED",
                LiteratureSearchService.RESULT_SCHEMA_VERSION,
                LiteratureValidationService.RESULT_SCHEMA_VERSION,
                write(Map.of("pubmedSearch", pubmed,
                        "clinicalTrialsSearch", clinicalTrials)),
                write(result), null, null,
                write(List.of(Map.of(
                        "tool", "CROSSREF_REST_API",
                        "version", result.toolVersion(),
                        "requestCount", result.externalRequestCount(),
                        "cacheHitCount", result.cacheHitCount()))),
                null, null, started, clock.instant(), false, null, null);
        output.set("literatureValidation", json.valueToTree(result));
        commit(
                claim, List.of(step),
                "STEP_11_ANALYZE_SIMILAR_RESEARCH", "QUEUED",
                write(output), null,
                List.of(event(
                        claim, "completed", "LITERATURE_VALIDATION_COMPLETED",
                        "STEP_10_VALIDATE_LITERATURE", result)));
    }

    private void executeSimilarResearchAnalysis(
            AgentWorkflowRepository.TaskData task,
            AgentWorkflowRepository.ClaimHandle claim, int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        var peco = treeToValue(output.get("peco"), PecoDefinition.class);
        var strategy = treeToValue(
                output.get("searchStrategy"), SearchStrategy.class);
        var pubmed = treeToValue(
                output.get("pubmedSearch"), PubMedSearchModels.SearchResult.class);
        var clinicalTrials = treeToValue(
                output.get("clinicalTrialsSearch"),
                ClinicalTrialsSearchModels.SearchResult.class);
        var validation = treeToValue(
                output.get("literatureValidation"),
                LiteratureValidationModels.ValidationResult.class);
        var analysisRequest = Map.of(
                "peco", peco,
                "searchStrategy", strategy,
                "pubmedSearch", pubmed,
                "clinicalTrialsSearch", clinicalTrials,
                "literatureValidation", validation);
        var result = toolCalls.invoke(
                claim, "DETERMINISTIC_PECO_OVERLAP", analysisRequest,
                SimilarResearchAnalysisModels.AnalysisResult.class,
                () -> similarResearchAnalysis.execute(
                        task.hospitalId(), task.projectId(), task.id(), peco, strategy,
                        pubmed, clinicalTrials, validation));
        if (cancelled(task)) return;
        var step = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_11_ANALYZE_SIMILAR_RESEARCH", attempt, "COMPLETED",
                "similar-research-analysis-input/v1",
                SimilarResearchAnalysisService.RESULT_SCHEMA_VERSION,
                write(Map.of(
                        "peco", peco,
                        "searchStrategy", strategy,
                        "pubmedSearch", pubmed,
                        "clinicalTrialsSearch", clinicalTrials,
                        "literatureValidation", validation)),
                write(result), null, null,
                write(List.of(Map.of(
                        "tool", "DETERMINISTIC_PECO_OVERLAP",
                        "version", result.algorithmVersion(),
                        "sourceCount", result.analyzedSourceCount()))),
                null, null, started, clock.instant(), false, null, null);
        output.set("similarResearchAnalysis", json.valueToTree(result));
        commit(
                claim, List.of(step),
                "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN", "QUEUED",
                write(output), null,
                List.of(event(
                        claim, "completed", "SIMILAR_RESEARCH_ANALYSIS_COMPLETED",
                        "STEP_11_ANALYZE_SIMILAR_RESEARCH", result)));
    }

    private void executeObservationalDesignRecommendation(
            AgentWorkflowRepository.TaskData task,
            AgentWorkflowRepository.ClaimHandle claim,
            AgentWorkflowService.TaskInput input,
            int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        var analysis = treeToValue(output.get("analysis"), AnalysisResult.class);
        var peco = treeToValue(output.get("peco"), PecoDefinition.class);
        var similar = treeToValue(
                output.get("similarResearchAnalysis"),
                SimilarResearchAnalysisModels.AnalysisResult.class);
        var designRequest = Map.of(
                "analysis", analysis,
                "peco", peco,
                "clarificationAnswers", answers(input),
                "similarResearchAnalysis", similar);
        var result = toolCalls.invoke(
                claim, "OBSERVATIONAL_STUDY_RULES", designRequest,
                ObservationalDesignRecommendationModels.Recommendation.class,
                () -> observationalDesign.execute(
                        task.hospitalId(), task.projectId(), task.id(),
                        analysis, peco, answers(input), similar));
        if (cancelled(task)) return;
        var step = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN", attempt,
                "WAITING_CONFIRMATION",
                "observational-design-recommendation-input/v1",
                ObservationalDesignRecommendationService.RESULT_SCHEMA_VERSION,
                write(Map.of(
                        "analysis", analysis,
                        "peco", peco,
                        "clarificationAnswers", answers(input),
                        "similarResearchAnalysis", similar)),
                write(result), null, null,
                write(List.of(Map.of(
                        "tool", "OBSERVATIONAL_STUDY_RULES",
                        "version", result.algorithmVersion(),
                        "alternativeCount", result.alternatives().size()))),
                null, null, started, null, true, null, null);
        output.set("observationalDesignRecommendation", json.valueToTree(result));
        commit(
                claim, List.of(step),
                "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN",
                "WAITING_CONFIRMATION", write(output), null,
                List.of(event(
                        claim, "waiting",
                        "WAITING_OBSERVATIONAL_DESIGN_CONFIRMATION",
                        "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN", result)));
    }

    private void executeProtocolSections(
            AgentWorkflowRepository.TaskData task,
            AgentWorkflowRepository.ClaimHandle claim,
            int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        var analysis = treeToValue(output.get("analysis"), AnalysisResult.class);
        var peco = treeToValue(output.get("peco"), PecoDefinition.class);
        var design = treeToValue(
                output.get("observationalDesignRecommendation"),
                ObservationalDesignRecommendationModels.Recommendation.class);
        var similar = treeToValue(
                output.get("similarResearchAnalysis"),
                SimilarResearchAnalysisModels.AnalysisResult.class);
        var protocolRequest = Map.of(
                "analysis", analysis,
                "peco", peco,
                "confirmedObservationalDesign", design,
                "similarResearchAnalysis", similar);
        var result = toolCalls.invoke(
                claim, "DETERMINISTIC_OBSERVATIONAL_PROTOCOL", protocolRequest,
                ResearchProtocolModels.ProtocolDraft.class,
                () -> protocolGeneration.execute(
                        task.hospitalId(), task.projectId(), task.id(),
                        analysis, peco, design, similar));
        if (cancelled(task)) return;
        var step = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_13_GENERATE_PROTOCOL_SECTIONS", attempt, "COMPLETED",
                "research-protocol-generation-input/v1",
                ResearchProtocolGenerationService.RESULT_SCHEMA_VERSION,
                write(Map.of(
                        "analysis", analysis,
                        "peco", peco,
                        "confirmedObservationalDesign", design,
                        "similarResearchAnalysis", similar)),
                write(result), null, null,
                write(List.of(Map.of(
                        "tool", "DETERMINISTIC_OBSERVATIONAL_PROTOCOL",
                        "version", result.generatorVersion(),
                        "sectionCount", result.sections().size()))),
                null, null, started, clock.instant(), false, null, null);
        output.set("protocolDraft", json.valueToTree(result));
        commit(
                claim, List.of(step),
                "STEP_14_GENERATE_STATISTICAL_DRAFT", "QUEUED",
                write(output), null,
                List.of(event(
                        claim, "completed", "PROTOCOL_SECTIONS_GENERATED",
                        "STEP_13_GENERATE_PROTOCOL_SECTIONS", result)));
    }

    private void executeStatisticalDraft(
            AgentWorkflowRepository.TaskData task,
            AgentWorkflowRepository.ClaimHandle claim,
            int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        var protocol = treeToValue(
                output.get("protocolDraft"),
                ResearchProtocolModels.ProtocolDraft.class);
        var design = treeToValue(
                output.get("observationalDesignRecommendation"),
                ObservationalDesignRecommendationModels.Recommendation.class);
        var statisticsRequest = Map.of(
                "protocolDraft", protocol,
                "confirmedObservationalDesign", design);
        var result = toolCalls.invoke(
                claim, "DETERMINISTIC_OBSERVATIONAL_STATISTICS",
                statisticsRequest, StatisticalAnalysisModels.StatisticalDraft.class,
                () -> statisticalAnalysis.execute(
                        task.hospitalId(), task.projectId(), task.id(),
                        protocol, design));
        if (cancelled(task)) return;
        var step = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_14_GENERATE_STATISTICAL_DRAFT", attempt, "COMPLETED",
                "statistical-analysis-input/v1",
                StatisticalAnalysisDraftService.RESULT_SCHEMA_VERSION,
                write(Map.of(
                        "protocolDraft", protocol,
                        "confirmedObservationalDesign", design)),
                write(result), null, null,
                write(List.of(Map.of(
                        "tool", "DETERMINISTIC_OBSERVATIONAL_STATISTICS",
                        "version", result.generatorVersion(),
                        "sampleSizeParameterCount",
                        result.sampleSizeParameters().size()))),
                null, null, started, clock.instant(), false, null, null);
        output.set("statisticalAnalysisDraft", json.valueToTree(result));
        output.set(
                "protocolDraft",
                json.valueToTree(statisticalAnalysis.applyToProtocol(protocol, result)));
        commit(
                claim, List.of(step),
                "STEP_15_VALIDATE_CLAIMS_AND_CITATIONS", "QUEUED",
                write(output), null,
                List.of(event(
                        claim, "completed", "STATISTICAL_DRAFT_GENERATED",
                        "STEP_14_GENERATE_STATISTICAL_DRAFT", result)));
    }

    private void executeClaimCitationValidation(
            AgentWorkflowRepository.TaskData task,
            AgentWorkflowRepository.ClaimHandle claim,
            int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        var protocol = treeToValue(
                output.get("protocolDraft"),
                ResearchProtocolModels.ProtocolDraft.class);
        var pubmed = treeToValue(
                output.get("pubmedSearch"),
                PubMedSearchModels.SearchResult.class);
        var validation = treeToValue(
                output.get("literatureValidation"),
                LiteratureValidationModels.ValidationResult.class);
        var citationRequest = Map.of(
                "protocolDraft", protocol,
                "pubmedSearch", pubmed,
                "literatureValidation", validation);
        var result = toolCalls.invoke(
                claim, "DETERMINISTIC_CLAIM_CITATION_LINKER",
                citationRequest,
                ClaimCitationValidationModels.ValidationResult.class,
                () -> claimCitationValidation.execute(
                        task.hospitalId(), task.projectId(), task.id(),
                        protocol, pubmed, validation));
        if (cancelled(task)) return;
        var step = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_15_VALIDATE_CLAIMS_AND_CITATIONS", attempt, "COMPLETED",
                "claim-citation-validation-input/v1",
                ClaimCitationValidationService.RESULT_SCHEMA_VERSION,
                write(Map.of(
                        "protocolDraft", protocol,
                        "pubmedSearch", pubmed,
                        "literatureValidation", validation)),
                write(result), null, null,
                write(List.of(Map.of(
                        "tool", "DETERMINISTIC_CLAIM_CITATION_LINKER",
                        "version", result.validatorVersion(),
                        "claimCount", result.claimCount(),
                        "citationLinkCount", result.citationLinkCount()))),
                null, null, started, clock.instant(), false, null, null);
        output.set("claimCitationValidation", json.valueToTree(result));
        commit(
                claim, List.of(step),
                "STEP_16_CHECK_STROBE_COMPLETENESS", "QUEUED",
                write(output), null,
                List.of(event(
                        claim, "completed", "CLAIMS_AND_CITATIONS_VALIDATED",
                        "STEP_15_VALIDATE_CLAIMS_AND_CITATIONS", result)));
    }

    private void executeStrobeCompletenessCheck(
            AgentWorkflowRepository.TaskData task,
            AgentWorkflowRepository.ClaimHandle claim,
            int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        var protocol = treeToValue(
                output.get("protocolDraft"),
                ResearchProtocolModels.ProtocolDraft.class);
        var statisticalDraft = treeToValue(
                output.get("statisticalAnalysisDraft"),
                StatisticalAnalysisModels.StatisticalDraft.class);
        var claimValidation = treeToValue(
                output.get("claimCitationValidation"),
                ClaimCitationValidationModels.ValidationResult.class);
        var strobeRequest = Map.of(
                "protocolDraft", protocol,
                "statisticalAnalysisDraft", statisticalDraft,
                "claimCitationValidation", claimValidation);
        var result = toolCalls.invoke(
                claim, "DETERMINISTIC_STROBE_2007_PRECHECK",
                strobeRequest, StrobeCompletenessModels.CheckResult.class,
                () -> strobeCompleteness.execute(
                        task.hospitalId(), task.projectId(), task.id(),
                        protocol, statisticalDraft, claimValidation));
        if (cancelled(task)) return;
        var step16 = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_16_CHECK_STROBE_COMPLETENESS", attempt, "COMPLETED",
                "strobe-completeness-check-input/v1",
                StrobeCompletenessService.RESULT_SCHEMA_VERSION,
                write(Map.of(
                        "protocolDraft", protocol,
                        "statisticalAnalysisDraft", statisticalDraft,
                        "claimCitationValidation", claimValidation)),
                write(result), null, null,
                write(List.of(Map.of(
                        "tool", "DETERMINISTIC_STROBE_2007_PRECHECK",
                        "version", result.checkerVersion(),
                        "itemCount", result.totalItemCount()))),
                null, null, started, clock.instant(), false, null, null);
        output.set("strobeCompletenessCheck", json.valueToTree(result));
        var review = toolCalls.invoke(
                claim, "OPEN_EXPERT_REVIEW",
                Map.of(
                        "protocolId", protocol.protocolId(),
                        "strobeCheckTaskId", result.checkTaskId()),
                ExpertReviewModels.ReviewView.class,
                () -> expertReview.open(
                        task, protocol.protocolId(), result.checkTaskId(),
                        write(output)));
        output.set("expertReview", json.valueToTree(review));
        var step17 = new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_17_WAIT_EXPERT_REVIEW", attempt, "WAITING_CONFIRMATION",
                "expert-review-input/v1",
                ExpertReviewService.REVIEW_SCHEMA_VERSION,
                write(Map.of(
                        "protocolId", protocol.protocolId(),
                        "strobeCheckTaskId", result.checkTaskId())),
                write(review), null, null,
                write(List.of()), null, null, clock.instant(), null,
                true, null, null);
        commit(
                claim, List.of(step16, step17),
                "STEP_17_WAIT_EXPERT_REVIEW", "WAITING_CONFIRMATION",
                write(output), null,
                List.of(
                        event(
                                claim, "strobe", "STROBE_COMPLETENESS_CHECKED",
                                "STEP_16_CHECK_STROBE_COMPLETENESS", result),
                        event(
                                claim, "review", "EXPERT_REVIEW_REQUIRED",
                                "STEP_17_WAIT_EXPERT_REVIEW", review)));
    }

    private AgentWorkflowRepository.StepData completedStep(
            AgentWorkflowRepository.TaskData task, String stepCode,
            int attempt, Object input, Object output, Instant started,
            String promptStep, String toolCallsJson, UUID modelCallId) {
        Instant completed = clock.instant();
        String promptVersion = promptStep == null ? null : prompts.require(promptStep).version();
        return new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(), stepCode, attempt,
                "COMPLETED", "research-workflow/v1", "research-workflow/v1",
                write(input), write(output), modelCallId,
                promptVersion, toolCallsJson,
                null, null, started, completed,
                false, null, null);
    }

    private AgentWorkflowRepository.PendingEvent event(
            AgentWorkflowRepository.ClaimHandle claim, String suffix,
            String eventType, String stepCode, Object payload) {
        return new AgentWorkflowRepository.PendingEvent(
                claim.stepAttemptId() + ":" + suffix,
                eventType, stepCode, write(payload));
    }

    private void commit(
            AgentWorkflowRepository.ClaimHandle claim,
            List<AgentWorkflowRepository.StepData> steps,
            String nextStep, String nextStatus, String outputJson,
            Instant completedAt,
            List<AgentWorkflowRepository.PendingEvent> events) {
        var outcome = repository.commitClaim(
                claim, steps,
                new AgentWorkflowRepository.TaskTransition(
                        nextStep, nextStatus, outputJson, completedAt),
                events, clock.instant());
        publishApplied(outcome);
    }

    private void failClaim(
            AgentWorkflowRepository.ClaimHandle claim,
            String errorCode, String persistedMessage, String publicMessage) {
        var outcome = repository.failClaim(
                claim, errorCode, persistedMessage,
                event(claim, "failed", "TASK_FAILED", claim.stepCode(),
                        new FailurePayload(errorCode, publicMessage)),
                clock.instant());
        publishApplied(outcome);
    }

    private void publishApplied(AgentWorkflowRepository.CommitOutcome outcome) {
        if (outcome.status() == AgentWorkflowRepository.CommitStatus.APPLIED) {
            service.publishCommitted(outcome.events());
        }
    }

    private boolean cancelled(AgentWorkflowRepository.TaskData task) {
        return repository.findById(task.hospitalId(), task.id())
                .map(current -> current.cancelRequested() || "CANCELLED".equals(current.status()))
                .orElse(true);
    }

    private boolean renewClaim(AgentWorkflowRepository.ClaimHandle claim) {
        Instant heartbeatAt = clock.instant();
        return repository.heartbeat(
                claim, heartbeatAt.plus(leaseDuration), heartbeatAt);
    }

    private String contextualIdea(AgentWorkflowService.TaskInput input) {
        if (answers(input).isEmpty()) return input.idea();
        StringBuilder context = new StringBuilder(input.idea()).append("\n已确认补充信息：");
        answers(input).forEach((question, answer) ->
                context.append("\n- ").append(question).append("：").append(answer));
        return context.toString();
    }

    private Map<String, String> answers(AgentWorkflowService.TaskInput input) {
        return input.clarificationAnswers() == null ? Map.of() : input.clarificationAnswers();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("研究方向候选集哈希失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent事件序列化失败", exception);
        }
    }

    private ObjectNode readOutput(String value) {
        try {
            var parsed = json.readTree(value);
            if (!(parsed instanceof ObjectNode output)) {
                throw new IllegalStateException("Agent任务缺少结构化输出");
            }
            return output;
        } catch (Exception exception) {
            throw new IllegalStateException("Agent任务输出损坏", exception);
        }
    }

    private <T> T treeToValue(com.fasterxml.jackson.databind.JsonNode value, Class<T> type) {
        if (value == null || value.isNull()) {
            throw new IllegalStateException("Agent任务缺少待执行检索策略");
        }
        try {
            return json.treeToValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent检索策略数据损坏", exception);
        }
    }

    record StepPayload(String stepCode, int attempt) {}
    record FailurePayload(String code, String message) {}
}
