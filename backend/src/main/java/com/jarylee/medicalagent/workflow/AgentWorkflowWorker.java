package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarylee.medicalagent.agent.PrototypeService;
import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import com.jarylee.medicalagent.agent.model.ResearchModels.PecoDefinition;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class AgentWorkflowWorker {
    private final AgentWorkflowRepository repository;
    private final AgentWorkflowService service;
    private final PrototypeService prototype;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration leaseDuration;
    private final PromptTemplateRegistry prompts;
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

    public AgentWorkflowWorker(AgentWorkflowRepository repository, AgentWorkflowService service,
                               PrototypeService prototype, ObjectMapper json, Clock clock,
                               @Value("${medical.agent.lease-duration:30s}") Duration leaseDuration,
                               PromptTemplateRegistry prompts,
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
        this.prompts = prompts;
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
            repository.fail(task.hospitalId(), task.id(), "TASK_TIMEOUT", "Agent任务执行超时");
            var failed = repository.findById(task.hospitalId(), task.id()).orElse(task);
            service.publish(failed, "TASK_FAILED", failed.currentStep(),
                    write(new FailurePayload("TASK_TIMEOUT", "Agent任务执行超时")));
        }
        for (var task : repository.findRunnable(now, 5)) {
            if (repository.claim(task.hospitalId(), task.id(), task.version(), now.plus(leaseDuration))) {
                execute(task);
            }
        }
    }

    void execute(AgentWorkflowRepository.TaskData claimedFrom) {
        var task = repository.findById(claimedFrom.hospitalId(), claimedFrom.id())
                .orElseThrow();
        int attempt = Math.toIntExact(task.version());
        service.publish(task, "TASK_STARTED", task.currentStep(),
                write(new AgentWorkflowService.StatusPayload("RUNNING")));
        try {
            AgentWorkflowService.TaskInput input =
                    json.readValue(task.inputJson(), AgentWorkflowService.TaskInput.class);
            if ("STEP_01_PARSE_IDEA".equals(task.currentStep())) {
                executeAnalysis(task, input, attempt);
            } else if ("STEP_04_GENERATE_RESEARCH_DIRECTIONS".equals(task.currentStep())) {
                executeDirections(task, input, attempt);
            } else if ("STEP_05_CONFIRM_DIRECTION".equals(task.currentStep())) {
                executeConfirmation(task, input, attempt);
            } else if ("STEP_08_SEARCH_PUBMED".equals(task.currentStep())) {
                executePubMedSearch(task, attempt);
            } else if ("STEP_09_SEARCH_CLINICAL_TRIALS".equals(task.currentStep())) {
                executeClinicalTrialsSearch(task, attempt);
            } else if ("STEP_10_VALIDATE_LITERATURE".equals(task.currentStep())) {
                executeLiteratureValidation(task, attempt);
            } else if ("STEP_11_ANALYZE_SIMILAR_RESEARCH".equals(task.currentStep())) {
                executeSimilarResearchAnalysis(task, attempt);
            } else if ("STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN".equals(task.currentStep())) {
                executeObservationalDesignRecommendation(task, input, attempt);
            } else if ("STEP_13_GENERATE_PROTOCOL_SECTIONS".equals(task.currentStep())) {
                executeProtocolSections(task, attempt);
            } else if ("STEP_14_GENERATE_STATISTICAL_DRAFT".equals(task.currentStep())) {
                executeStatisticalDraft(task, attempt);
            } else if ("STEP_15_VALIDATE_CLAIMS_AND_CITATIONS".equals(task.currentStep())) {
                executeClaimCitationValidation(task, attempt);
            } else if ("STEP_16_CHECK_STROBE_COMPLETENESS".equals(task.currentStep())) {
                executeStrobeCompletenessCheck(task, attempt);
            } else {
                throw new IllegalStateException("不支持的Agent步骤: " + task.currentStep());
            }
        } catch (PubMedSearchException exception) {
            repository.fail(task.hospitalId(), task.id(), exception.code(), exception.getMessage());
            var failed = repository.findById(task.hospitalId(), task.id()).orElse(task);
            service.publish(failed, "TASK_FAILED", failed.currentStep(),
                    write(new FailurePayload(exception.code(), exception.getMessage())));
        } catch (ClinicalTrialsSearchException exception) {
            repository.fail(task.hospitalId(), task.id(), exception.code(), exception.getMessage());
            var failed = repository.findById(task.hospitalId(), task.id()).orElse(task);
            service.publish(failed, "TASK_FAILED", failed.currentStep(),
                    write(new FailurePayload(exception.code(), exception.getMessage())));
        } catch (CrossrefMetadataException exception) {
            repository.fail(task.hospitalId(), task.id(), exception.code(), exception.getMessage());
            var failed = repository.findById(task.hospitalId(), task.id()).orElse(task);
            service.publish(failed, "TASK_FAILED", failed.currentStep(),
                    write(new FailurePayload(exception.code(), exception.getMessage())));
        } catch (SimilarResearchAnalysisException exception) {
            repository.fail(task.hospitalId(), task.id(), exception.code(), exception.getMessage());
            var failed = repository.findById(task.hospitalId(), task.id()).orElse(task);
            service.publish(failed, "TASK_FAILED", failed.currentStep(),
                    write(new FailurePayload(exception.code(), exception.getMessage())));
        } catch (Exception exception) {
            repository.fail(task.hospitalId(), task.id(), "AGENT_STEP_FAILED", exception.getMessage());
            var failed = repository.findById(task.hospitalId(), task.id()).orElse(task);
            service.publish(failed, "TASK_FAILED", failed.currentStep(),
                    write(new FailurePayload("AGENT_STEP_FAILED", "Agent步骤执行失败")));
        }
    }

    private void executeAnalysis(AgentWorkflowRepository.TaskData task,
                                 AgentWorkflowService.TaskInput input, int attempt) {
        Instant started = clock.instant();
        var analysis = prototype.analyze(input.idea());
        if (cancelled(task)) return;
        saveCompletedStep(task, "STEP_01_PARSE_IDEA", attempt, input,
                analysis.profile(), started, "STEP_01_PARSE_IDEA", "[]");
        saveCompletedStep(task, "STEP_02_IDENTIFY_MISSING_INFORMATION", attempt,
                analysis.profile(), analysis.profile().missingInformation(), started, null, "[]");
        repository.saveStep(new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(), "STEP_03_ASK_CLARIFICATION",
                attempt, "WAITING_CONFIRMATION", "missing-information/v1",
                "clarification-answers/v1", write(analysis.clarificationQuestions()), null,
                null, null, "[]", null, null, clock.instant(), null, true, null, null));
        var clarification = new AgentWorkflowService.ClarificationOutput(
                analysis.profile(), analysis.clarificationQuestions(), analysis.disclaimer());
        repository.waitForClarification(task.hospitalId(), task.id(), write(clarification));
        var waiting = repository.findById(task.hospitalId(), task.id()).orElse(task);
        service.publish(waiting, "WAITING_CLARIFICATION", "STEP_03_ASK_CLARIFICATION",
                write(clarification));
    }

    private void executeDirections(AgentWorkflowRepository.TaskData task,
                                   AgentWorkflowService.TaskInput input, int attempt) {
        Instant started = clock.instant();
        var analysis = prototype.analyze(contextualIdea(input));
        if (cancelled(task)) return;
        saveCompletedStep(task, "STEP_04_GENERATE_RESEARCH_DIRECTIONS", attempt,
                input, analysis.directions(), started,
                "STEP_04_GENERATE_RESEARCH_DIRECTIONS", "[]");
        repository.saveStep(new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(), "STEP_05_CONFIRM_DIRECTION",
                attempt, "WAITING_CONFIRMATION", "direction-candidates/v1",
                "direction-confirmation/v1", write(analysis.directions()), null,
                null, null, "[]", null, null, clock.instant(), null, true, null, null));
        repository.waitForConfirmation(task.hospitalId(), task.id(), write(analysis));
        var waiting = repository.findById(task.hospitalId(), task.id()).orElse(task);
        service.publish(waiting, "WAITING_CONFIRMATION", "STEP_05_CONFIRM_DIRECTION",
                write(analysis));
    }

    private void executeConfirmation(AgentWorkflowRepository.TaskData task,
                                     AgentWorkflowService.TaskInput input, int attempt) {
        if (input.directionId() == null || input.directionId().isBlank()) {
            throw new IllegalArgumentException("缺少已确认研究方向");
        }
        Instant started = clock.instant();
        var result = prototype.buildResearchQuestion(contextualIdea(input), input.directionId());
        if (cancelled(task)) return;
        saveCompletedStep(task, "STEP_06_BUILD_RESEARCH_QUESTION", attempt,
                new AgentWorkflowService.DirectionPayload(input.directionId()), result.peco(), started,
                "STEP_06_BUILD_RESEARCH_QUESTION", "[]");
        var assessment = studyRules.assess(
                result.selectedDirection().recommendedStudyType(), result.analysis(), answers(input));
        ObjectNode output = json.valueToTree(result);
        output.set("designAssessment", json.valueToTree(assessment));
        var strategy = searchStrategies.generate(result.peco());
        output.set("searchStrategy", json.valueToTree(strategy));
        repository.saveStep(new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_07_BUILD_SEARCH_STRATEGY", attempt, "WAITING_CONFIRMATION",
                "peco/v1", SearchStrategyService.SCHEMA_VERSION,
                write(result.peco()), write(strategy), null, null, "[]",
                null, null, clock.instant(), null, true, null, null));
        repository.waitForSearchStrategyConfirmation(
                task.hospitalId(), task.id(), write(output));
        var waiting = repository.findById(task.hospitalId(), task.id()).orElse(task);
        service.publish(waiting, "WAITING_SEARCH_STRATEGY",
                "STEP_07_BUILD_SEARCH_STRATEGY",
                write(output));
    }

    private void executePubMedSearch(
            AgentWorkflowRepository.TaskData task, int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        SearchStrategy strategy = treeToValue(output.get("searchStrategy"), SearchStrategy.class);
        var result = literatureSearch.execute(
                task.hospitalId(), task.projectId(), task.id(), strategy);
        if (cancelled(task)) return;
        repository.saveStep(new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_08_SEARCH_PUBMED", attempt, "COMPLETED",
                SearchStrategyService.SCHEMA_VERSION,
                LiteratureSearchService.RESULT_SCHEMA_VERSION,
                write(strategy), write(result), null, null,
                write(List.of(Map.of(
                        "tool", "NCBI_EUTILS",
                        "version", result.toolVersion(),
                        "requestCount", result.externalRequestCount()))),
                null, null, started, clock.instant(), false, null, null));
        output.set("pubmedSearch", json.valueToTree(result));
        repository.queueClinicalTrialsSearch(
                task.hospitalId(), task.id(), write(output));
        var queued = repository.findById(task.hospitalId(), task.id()).orElse(task);
        service.publish(queued, "LITERATURE_SEARCH_COMPLETED",
                "STEP_08_SEARCH_PUBMED", write(result));
    }

    private void executeClinicalTrialsSearch(
            AgentWorkflowRepository.TaskData task, int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        SearchStrategy strategy = treeToValue(output.get("searchStrategy"), SearchStrategy.class);
        var result = clinicalTrialsSearch.execute(
                task.hospitalId(), task.projectId(), task.id(), strategy);
        if (cancelled(task)) return;
        repository.saveStep(new AgentWorkflowRepository.StepData(
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
                null, null, started, clock.instant(), false, null, null));
        output.set("clinicalTrialsSearch", json.valueToTree(result));
        repository.queueLiteratureValidation(
                task.hospitalId(), task.id(), write(output));
        var queued = repository.findById(task.hospitalId(), task.id()).orElse(task);
        service.publish(queued, "CLINICAL_TRIALS_SEARCH_COMPLETED",
                "STEP_09_SEARCH_CLINICAL_TRIALS", write(result));
    }

    private void executeLiteratureValidation(
            AgentWorkflowRepository.TaskData task, int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        var pubmed = treeToValue(
                output.get("pubmedSearch"), PubMedSearchModels.SearchResult.class);
        var clinicalTrials = treeToValue(
                output.get("clinicalTrialsSearch"),
                ClinicalTrialsSearchModels.SearchResult.class);
        var result = literatureValidation.execute(
                task.hospitalId(), task.projectId(), task.id(), pubmed, clinicalTrials);
        if (cancelled(task)) return;
        repository.saveStep(new AgentWorkflowRepository.StepData(
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
                null, null, started, clock.instant(), false, null, null));
        output.set("literatureValidation", json.valueToTree(result));
        repository.queueSimilarResearchAnalysis(
                task.hospitalId(), task.id(), write(output));
        var queued = repository.findById(task.hospitalId(), task.id()).orElse(task);
        service.publish(queued, "LITERATURE_VALIDATION_COMPLETED",
                "STEP_10_VALIDATE_LITERATURE", write(result));
    }

    private void executeSimilarResearchAnalysis(
            AgentWorkflowRepository.TaskData task, int attempt) {
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
        var result = similarResearchAnalysis.execute(
                task.hospitalId(), task.projectId(), task.id(), peco, strategy,
                pubmed, clinicalTrials, validation);
        if (cancelled(task)) return;
        repository.saveStep(new AgentWorkflowRepository.StepData(
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
                null, null, started, clock.instant(), false, null, null));
        output.set("similarResearchAnalysis", json.valueToTree(result));
        repository.queueObservationalDesignRecommendation(
                task.hospitalId(), task.id(), write(output));
        var queued = repository.findById(task.hospitalId(), task.id()).orElse(task);
        service.publish(queued, "SIMILAR_RESEARCH_ANALYSIS_COMPLETED",
                "STEP_11_ANALYZE_SIMILAR_RESEARCH", write(result));
    }

    private void executeObservationalDesignRecommendation(
            AgentWorkflowRepository.TaskData task,
            AgentWorkflowService.TaskInput input,
            int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        var analysis = treeToValue(output.get("analysis"), AnalysisResult.class);
        var peco = treeToValue(output.get("peco"), PecoDefinition.class);
        var similar = treeToValue(
                output.get("similarResearchAnalysis"),
                SimilarResearchAnalysisModels.AnalysisResult.class);
        var result = observationalDesign.execute(
                task.hospitalId(), task.projectId(), task.id(),
                analysis, peco, answers(input), similar);
        if (cancelled(task)) return;
        repository.saveStep(new AgentWorkflowRepository.StepData(
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
                null, null, started, null, true, null, null));
        output.set("observationalDesignRecommendation", json.valueToTree(result));
        repository.waitForObservationalDesignConfirmation(
                task.hospitalId(), task.id(), write(output));
        var waiting = repository.findById(task.hospitalId(), task.id()).orElse(task);
        service.publish(
                waiting,
                "WAITING_OBSERVATIONAL_DESIGN_CONFIRMATION",
                "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN",
                write(result));
    }

    private void executeProtocolSections(
            AgentWorkflowRepository.TaskData task,
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
        var result = protocolGeneration.execute(
                task.hospitalId(), task.projectId(), task.id(),
                analysis, peco, design, similar);
        if (cancelled(task)) return;
        repository.saveStep(new AgentWorkflowRepository.StepData(
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
                null, null, started, clock.instant(), false, null, null));
        output.set("protocolDraft", json.valueToTree(result));
        repository.queueStatisticalDraft(
                task.hospitalId(), task.id(), write(output));
        var queued = repository.findById(task.hospitalId(), task.id()).orElse(task);
        service.publish(
                queued,
                "PROTOCOL_SECTIONS_GENERATED",
                "STEP_13_GENERATE_PROTOCOL_SECTIONS",
                write(result));
    }

    private void executeStatisticalDraft(
            AgentWorkflowRepository.TaskData task,
            int attempt) {
        Instant started = clock.instant();
        ObjectNode output = readOutput(task.outputJson());
        var protocol = treeToValue(
                output.get("protocolDraft"),
                ResearchProtocolModels.ProtocolDraft.class);
        var design = treeToValue(
                output.get("observationalDesignRecommendation"),
                ObservationalDesignRecommendationModels.Recommendation.class);
        var result = statisticalAnalysis.execute(
                task.hospitalId(), task.projectId(), task.id(), protocol, design);
        if (cancelled(task)) return;
        repository.saveStep(new AgentWorkflowRepository.StepData(
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
                null, null, started, clock.instant(), false, null, null));
        output.set("statisticalAnalysisDraft", json.valueToTree(result));
        output.set(
                "protocolDraft",
                json.valueToTree(statisticalAnalysis.applyToProtocol(protocol, result)));
        repository.queueClaimCitationValidation(
                task.hospitalId(), task.id(), write(output));
        var queued = repository.findById(task.hospitalId(), task.id()).orElse(task);
        service.publish(
                queued,
                "STATISTICAL_DRAFT_GENERATED",
                "STEP_14_GENERATE_STATISTICAL_DRAFT",
                write(result));
    }

    private void executeClaimCitationValidation(
            AgentWorkflowRepository.TaskData task,
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
        var result = claimCitationValidation.execute(
                task.hospitalId(), task.projectId(), task.id(),
                protocol, pubmed, validation);
        if (cancelled(task)) return;
        repository.saveStep(new AgentWorkflowRepository.StepData(
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
                null, null, started, clock.instant(), false, null, null));
        output.set("claimCitationValidation", json.valueToTree(result));
        repository.queueStrobeCompletenessCheck(
                task.hospitalId(), task.id(), write(output));
        var queued = repository.findById(task.hospitalId(), task.id()).orElse(task);
        service.publish(
                queued,
                "CLAIMS_AND_CITATIONS_VALIDATED",
                "STEP_15_VALIDATE_CLAIMS_AND_CITATIONS",
                write(result));
    }

    private void executeStrobeCompletenessCheck(
            AgentWorkflowRepository.TaskData task,
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
        var result = strobeCompleteness.execute(
                task.hospitalId(), task.projectId(), task.id(),
                protocol, statisticalDraft, claimValidation);
        if (cancelled(task)) return;
        repository.saveStep(new AgentWorkflowRepository.StepData(
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
                null, null, started, clock.instant(), false, null, null));
        output.set("strobeCompletenessCheck", json.valueToTree(result));
        var review = expertReview.open(
                task, protocol.protocolId(), result.checkTaskId());
        output.set("expertReview", json.valueToTree(review));
        repository.saveStep(new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(),
                "STEP_17_WAIT_EXPERT_REVIEW", attempt, "WAITING_CONFIRMATION",
                "expert-review-input/v1",
                ExpertReviewService.REVIEW_SCHEMA_VERSION,
                write(Map.of(
                        "protocolId", protocol.protocolId(),
                        "strobeCheckTaskId", result.checkTaskId())),
                write(review), null, null,
                write(List.of()), null, null, clock.instant(), null,
                true, null, null));
        repository.waitForExpertReview(
                task.hospitalId(), task.id(), write(output));
        var waiting = repository.findById(task.hospitalId(), task.id()).orElse(task);
        service.publish(
                waiting,
                "STROBE_COMPLETENESS_CHECKED",
                "STEP_16_CHECK_STROBE_COMPLETENESS",
                write(result));
        service.publish(
                waiting,
                "EXPERT_REVIEW_REQUIRED",
                "STEP_17_WAIT_EXPERT_REVIEW",
                write(review));
    }

    private void saveCompletedStep(AgentWorkflowRepository.TaskData task, String stepCode,
                                   int attempt, Object input, Object output, Instant started,
                                   String promptStep, String toolCallsJson) {
        Instant completed = clock.instant();
        String promptVersion = promptStep == null ? null : prompts.require(promptStep).version();
        repository.saveStep(new AgentWorkflowRepository.StepData(
                UUID.randomUUID(), task.hospitalId(), task.id(), stepCode, attempt,
                "COMPLETED", "research-workflow/v1", "research-workflow/v1",
                write(input), write(output), promptStep == null ? null : UUID.randomUUID(),
                promptVersion, toolCallsJson,
                null, null, started, completed,
                false, null, null));
        service.publish(task, "STEP_COMPLETED", stepCode,
                write(new StepPayload(stepCode, attempt)));
    }

    private boolean cancelled(AgentWorkflowRepository.TaskData task) {
        return repository.findById(task.hospitalId(), task.id())
                .map(current -> current.cancelRequested() || "CANCELLED".equals(current.status()))
                .orElse(true);
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
