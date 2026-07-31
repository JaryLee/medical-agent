package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.ResearchIdeaProfile;
import com.jarylee.medicalagent.agent.model.ResearchModels.ResearchDirection;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.literature.SearchStrategyService;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchStrategy;
import com.jarylee.medicalagent.research.ResearchProjectService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentWorkflowService {
    private final AgentWorkflowRepository repository;
    private final CurrentUserProvider currentUser;
    private final ResearchProjectService projects;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration taskTimeout;
    private final AgentEventStream eventStream;
    private final SearchStrategyService searchStrategies;
    private final ObservationalDesignRecommendationService observationalDesign;

    public AgentWorkflowService(AgentWorkflowRepository repository, CurrentUserProvider currentUser,
                                ResearchProjectService projects, AuditService audit, ObjectMapper json,
                                Clock clock, @Value("${medical.agent.task-timeout:15m}") Duration taskTimeout,
                                AgentEventStream eventStream,
                                SearchStrategyService searchStrategies,
                                ObservationalDesignRecommendationService observationalDesign) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.projects = projects;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.taskTimeout = taskTimeout;
        this.eventStream = eventStream;
        this.searchStrategies = searchStrategies;
        this.observationalDesign = observationalDesign;
    }

    @Transactional
    public TaskView create(UUID projectId, String idea, String idempotencyKey) {
        AuthenticatedUser actor = requireReadyUser();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少 Idempotency-Key");
        }
        projects.requireEditable(projectId);
        var existing = repository.findByIdempotency(
                actor.hospitalId(), actor.userId(), idempotencyKey);
        if (existing.isPresent()) return view(existing.get());

        Instant now = clock.instant();
        String inputJson = write(new TaskInput(idea.strip(), Map.of(), null, null, null));
        var task = new AgentWorkflowRepository.TaskData(
                UUID.randomUUID(), actor.hospitalId(), projectId, actor.userId(),
                "STEP_01_PARSE_IDEA", "QUEUED", inputJson, null, null,
                now.plus(taskTimeout), false, 0, null, null, now, now, null);
        task = repository.create(task, idempotencyKey);
        publish(task, "TASK_CREATED", task.currentStep(), write(new StatusPayload(task.status())));
        audit.record(actor, "AGENT_TASK_CREATED", "AI_AGENT_TASK", task.id().toString());
        return view(task);
    }

    public TaskView get(UUID taskId) {
        AuthenticatedUser actor = requireReadyUser();
        return view(requireTask(actor, taskId));
    }

    public List<TaskView> list(UUID projectId) {
        AuthenticatedUser actor = requireReadyUser();
        projects.get(projectId);
        return repository.findByProject(actor.hospitalId(), projectId).stream()
                .map(this::view).toList();
    }

    @Transactional
    public TaskView confirm(
            UUID taskId, String directionId, UUID candidateSetId, String candidateSetHash) {
        AuthenticatedUser actor = requireReadyUser();
        var task = requireEditableTask(actor, taskId);
        JsonNode output = readTree(task.outputJson());
        if (output == null
                || !candidateSetId.toString().equals(output.path("candidateSetId").asText())
                || !candidateSetHash.equals(output.path("candidateSetHash").asText())) {
            throw BusinessException.conflict("研究方向候选集已变化，请刷新后重新确认");
        }
        JsonNode directions = output.get("directions");
        if (directions == null || !directions.isArray()
                || !containsDirection(directions, directionId)) {
            throw BusinessException.conflict("所选研究方向不属于当前候选集");
        }
        TaskInput input = read(task.inputJson(), TaskInput.class);
        Instant now = clock.instant();
        boolean updated = repository.confirm(actor.hospitalId(), taskId,
                write(new TaskInput(input.idea(), answers(input), directionId,
                        candidateSetId, candidateSetHash)),
                actor.userId(), now, now.plus(taskTimeout));
        if (!updated) throw BusinessException.conflict("任务当前不可确认");
        var current = requireTask(actor, taskId);
        publish(current, "DIRECTION_CONFIRMED", "STEP_05_CONFIRM_DIRECTION",
                write(new DirectionPayload(directionId, candidateSetId, candidateSetHash)));
        audit.record(actor, "AGENT_DIRECTION_CONFIRMED", "AI_AGENT_TASK", taskId.toString());
        return view(current);
    }

    @Transactional
    public TaskView submitClarifications(UUID taskId, Map<String, String> submittedAnswers) {
        AuthenticatedUser actor = requireReadyUser();
        var task = requireEditableTask(actor, taskId);
        String sourceStep = task.currentStep();
        if (!List.of("STEP_03_ASK_CLARIFICATION", "STEP_05_CONFIRM_DIRECTION")
                .contains(sourceStep)
                || !"WAITING_CONFIRMATION".equals(task.status())) {
            throw BusinessException.conflict("任务当前不等待澄清答案");
        }
        List<String> questions = clarificationQuestions(task.outputJson());
        Map<String, String> normalized = normalizeAnswers(questions, submittedAnswers);
        TaskInput input = read(task.inputJson(), TaskInput.class);
        Map<String, String> effectiveAnswers = new LinkedHashMap<>(answers(input));
        effectiveAnswers.putAll(normalized);
        Instant now = clock.instant();
        var round = repository.confirmClarifications(
                actor.hospitalId(), taskId, sourceStep,
                write(new TaskInput(input.idea(), Map.copyOf(effectiveAnswers), null, null, null)),
                write(questions), write(effectiveAnswers), actor.userId(), now,
                now.plus(taskTimeout))
                .orElseThrow(() -> BusinessException.conflict("澄清答案已被其他请求提交"));
        var current = requireTask(actor, taskId);
        publish(current, "CLARIFICATIONS_CONFIRMED", sourceStep,
                write(new ClarificationPayload(
                        round.roundNo(), normalized.size(),
                        "STEP_05_CONFIRM_DIRECTION".equals(sourceStep))));
        audit.record(actor, "AGENT_CLARIFICATION_ROUND_CONFIRMED",
                "AI_AGENT_TASK", taskId.toString());
        return view(current);
    }

    public List<ClarificationRoundView> clarificationHistory(UUID taskId) {
        AuthenticatedUser actor = requireReadyUser();
        requireTask(actor, taskId);
        return repository.findClarificationRounds(actor.hospitalId(), taskId).stream()
                .map(round -> new ClarificationRoundView(
                        round.id(), round.roundNo(), round.sourceStep(),
                        readTree(round.questionsJson()), readTree(round.answersJson()),
                        round.submittedBy(), round.submittedAt()))
                .toList();
    }

    @Transactional
    public TaskView confirmSearchStrategy(UUID taskId, String pubmedQuery) {
        AuthenticatedUser actor = requireReadyUser();
        var task = requireEditableTask(actor, taskId);
        if (!"STEP_07_BUILD_SEARCH_STRATEGY".equals(task.currentStep())
                || !"WAITING_CONFIRMATION".equals(task.status())) {
            throw BusinessException.conflict("任务当前不等待检索策略确认");
        }
        JsonNode output = readTree(task.outputJson());
        JsonNode strategyNode = output == null ? null : output.get("searchStrategy");
        if (strategyNode == null || strategyNode.isNull()) {
            throw new IllegalStateException("任务缺少待确认检索策略");
        }
        SearchStrategy generated = read(strategyNode.toString(), SearchStrategy.class);
        SearchStrategy confirmed = searchStrategies.confirm(generated, pubmedQuery);
        JsonNode confirmedNode = json.valueToTree(confirmed);
        ((com.fasterxml.jackson.databind.node.ObjectNode) output)
                .set("searchStrategy", confirmedNode);
        Instant now = clock.instant();
        if (!repository.confirmSearchStrategy(
                actor.hospitalId(), taskId, write(output), write(confirmed),
                actor.userId(), now, now.plus(taskTimeout))) {
            throw BusinessException.conflict("检索策略已被其他请求确认");
        }
        var current = requireTask(actor, taskId);
        publish(current, "SEARCH_STRATEGY_CONFIRMED",
                "STEP_07_BUILD_SEARCH_STRATEGY", write(confirmed));
        audit.record(actor, "AGENT_SEARCH_STRATEGY_CONFIRMED",
                "AI_AGENT_TASK", taskId.toString());
        return view(current);
    }

    @Transactional
    public TaskView confirmObservationalDesign(
            UUID taskId,
            StudyType studyType,
            String primaryOutcome,
            boolean authorizeProtocolGeneration) {
        AuthenticatedUser actor = requireReadyUser();
        var task = requireEditableTask(actor, taskId);
        if (!"STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN".equals(task.currentStep())
                || !"WAITING_CONFIRMATION".equals(task.status())) {
            throw BusinessException.conflict("任务当前不等待观察性研究设计确认");
        }
        JsonNode output = readTree(task.outputJson());
        JsonNode recommendationNode = output == null
                ? null : output.get("observationalDesignRecommendation");
        if (recommendationNode == null || recommendationNode.isNull()) {
            throw new IllegalStateException("任务缺少待确认的观察性研究设计推荐");
        }
        var generated = read(
                recommendationNode.toString(),
                ObservationalDesignRecommendationModels.Recommendation.class);
        if (!generated.readyForProtocolDraft()) {
            throw BusinessException.conflict(
                    "设计所需信息尚不完整，不能授权进入正式研究方案生成");
        }
        Instant now = clock.instant();
        var confirmed = observationalDesign.confirm(
                generated,
                new ObservationalDesignRecommendationModels.Confirmation(
                        studyType, primaryOutcome, authorizeProtocolGeneration),
                actor.userId(),
                now);
        ((com.fasterxml.jackson.databind.node.ObjectNode) output)
                .set("observationalDesignRecommendation", json.valueToTree(confirmed));
        if (!repository.confirmObservationalDesign(
                actor.hospitalId(), taskId, write(output), write(confirmed),
                actor.userId(), now, now.plus(taskTimeout))) {
            throw BusinessException.conflict("观察性研究设计已被其他请求确认");
        }
        var current = requireTask(actor, taskId);
        publish(
                current,
                "OBSERVATIONAL_DESIGN_CONFIRMED",
                "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN",
                write(confirmed));
        publish(
                current,
                "PROTOCOL_GENERATION_QUEUED",
                "STEP_13_GENERATE_PROTOCOL_SECTIONS",
                write(new StatusPayload("QUEUED")));
        audit.record(
                actor,
                "AGENT_OBSERVATIONAL_DESIGN_CONFIRMED",
                "AI_AGENT_TASK",
                taskId.toString());
        return view(current);
    }

    @Transactional
    public TaskView cancel(UUID taskId) {
        AuthenticatedUser actor = requireReadyUser();
        requireEditableTask(actor, taskId);
        if (!repository.cancel(actor.hospitalId(), taskId, clock.instant())) {
            throw BusinessException.conflict("任务已结束，不能取消");
        }
        var current = requireTask(actor, taskId);
        publish(current, "TASK_CANCELLED", current.currentStep(), write(new StatusPayload("CANCELLED")));
        audit.record(actor, "AGENT_TASK_CANCELLED", "AI_AGENT_TASK", taskId.toString());
        return view(current);
    }

    @Transactional
    public TaskView retry(UUID taskId) {
        AuthenticatedUser actor = requireReadyUser();
        requireEditableTask(actor, taskId);
        if (!repository.retry(actor.hospitalId(), taskId, clock.instant().plus(taskTimeout))) {
            throw BusinessException.conflict("只有失败任务可以重试");
        }
        var current = requireTask(actor, taskId);
        publish(current, "TASK_RETRIED", current.currentStep(), write(new StatusPayload("QUEUED")));
        audit.record(actor, "AGENT_TASK_RETRIED", "AI_AGENT_TASK", taskId.toString());
        return view(current);
    }

    public SseEmitter events(UUID taskId, long afterEventId) {
        AuthenticatedUser actor = requireReadyUser();
        requireTask(actor, taskId);
        List<AgentWorkflowRepository.EventData> replay =
                repository.findEventsAfter(actor.hospitalId(), taskId, afterEventId);
        return eventStream.subscribe(taskId, replay);
    }

    void publish(AgentWorkflowRepository.TaskData task, String eventType,
                 String stepCode, String payloadJson) {
        var event = repository.appendEvent(task.hospitalId(), task.id(), eventType,
                stepCode, payloadJson, clock.instant());
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            eventStream.publish(event);
                        }
                    });
        } else {
            eventStream.publish(event);
        }
    }

    void publishCommitted(List<AgentWorkflowRepository.EventData> events) {
        events.forEach(eventStream::publish);
    }

    private AgentWorkflowRepository.TaskData requireTask(AuthenticatedUser actor, UUID taskId) {
        var task = repository.findById(actor.hospitalId(), taskId)
                .orElseThrow(() -> BusinessException.notFound("Agent任务不存在"));
        projects.get(task.projectId());
        return task;
    }

    private AgentWorkflowRepository.TaskData requireEditableTask(
            AuthenticatedUser actor, UUID taskId) {
        var task = requireTask(actor, taskId);
        projects.requireEditable(task.projectId());
        return task;
    }

    private AuthenticatedUser requireReadyUser() {
        AuthenticatedUser actor = currentUser.requireUser();
        if (actor.forcePasswordChange()) throw BusinessException.forbidden("首次登录必须先修改密码");
        if (actor.hospitalId() == null) throw BusinessException.forbidden("平台管理员不能运行医院课题任务");
        return actor;
    }

    private TaskView view(AgentWorkflowRepository.TaskData task) {
        return new TaskView(task.id(), task.projectId(), task.currentStep(), task.status(),
                readTree(task.inputJson()), readTree(task.outputJson()), task.version(),
                task.lastErrorCode(), task.lastErrorMessage(), task.createdAt(),
                task.updatedAt(), task.completedAt());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent任务序列化失败", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent任务数据损坏", exception);
        }
    }

    private JsonNode readTree(String value) {
        if (value == null) return null;
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent任务数据损坏", exception);
        }
    }

    private Map<String, String> normalizeAnswers(
            List<String> questions, Map<String, String> submittedAnswers) {
        if (submittedAnswers == null || !submittedAnswers.keySet().containsAll(questions)) {
            throw new IllegalArgumentException("必须逐项回答当前全部澄清问题");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String question : questions) {
            String answer = submittedAnswers.get(question);
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException("澄清答案不能为空");
            }
            String value = answer.strip();
            if (value.length() > 1000) throw new IllegalArgumentException("单项澄清答案不能超过1000字");
            normalized.put(question, value);
        }
        return Map.copyOf(normalized);
    }

    private List<String> clarificationQuestions(String outputJson) {
        JsonNode output = readTree(outputJson);
        JsonNode values = output == null ? null : output.get("clarificationQuestions");
        if (values == null || !values.isArray() || values.isEmpty()) {
            throw new IllegalStateException("任务缺少当前澄清问题");
        }
        List<String> questions = new ArrayList<>();
        values.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                questions.add(value.asText());
            }
        });
        if (questions.isEmpty()) throw new IllegalStateException("任务缺少当前澄清问题");
        return List.copyOf(questions);
    }

    private Map<String, String> answers(TaskInput input) {
        return input.clarificationAnswers() == null ? Map.of() : input.clarificationAnswers();
    }

    private boolean containsDirection(JsonNode directions, String directionId) {
        for (JsonNode direction : directions) {
            if (directionId.equals(direction.path("id").asText())) return true;
        }
        return false;
    }

    public record TaskInput(String idea, Map<String, String> clarificationAnswers,
                            String directionId, UUID directionCandidateSetId,
                            String directionCandidateSetHash) {}
    public record ClarificationOutput(ResearchIdeaProfile profile,
                                      List<String> clarificationQuestions,
                                      String disclaimer) {}
    public record StatusPayload(String status) {}
    public record DirectionPayload(
            String directionId, UUID candidateSetId, String candidateSetHash) {}
    public record DirectionCandidateSetPayload(
            UUID candidateSetId, String candidateSetHash,
            List<ResearchDirection> directions) {}
    public record ClarificationPayload(int roundNo, int answerCount, boolean directionRevision) {}
    public record ClarificationRoundView(
            UUID id, int roundNo, String sourceStep, JsonNode questions, JsonNode answers,
            UUID submittedBy, Instant submittedAt) {}
    public record TaskView(UUID id, UUID projectId, String currentStep, String status,
                           JsonNode input, JsonNode output, long version,
                           String errorCode, String errorMessage,
                           Instant createdAt, Instant updatedAt, Instant completedAt) {}
}
