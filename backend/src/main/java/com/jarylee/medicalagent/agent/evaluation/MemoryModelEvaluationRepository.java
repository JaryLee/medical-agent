package com.jarylee.medicalagent.agent.evaluation;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("memory")
public class MemoryModelEvaluationRepository
        implements ModelEvaluationRepository {
    private final Map<UUID, RunData> runs = new LinkedHashMap<>();
    private final Map<UUID, CaseData> cases = new LinkedHashMap<>();
    private final Map<UUID, ExpertScoreData> scores = new LinkedHashMap<>();

    @Override
    public synchronized void createRun(RunData run) {
        boolean duplicateKey = runs.values().stream().anyMatch(value ->
                value.hospitalId().equals(run.hospitalId())
                        && value.startedBy().equals(run.startedBy())
                        && value.idempotencyKey().equals(
                        run.idempotencyKey()));
        if (duplicateKey || runs.putIfAbsent(run.id(), run) != null) {
            throw new IllegalStateException("模型评测批次重复");
        }
    }

    @Override
    public synchronized void saveCaseResult(CaseData result) {
        boolean duplicate = cases.values().stream().anyMatch(value ->
                value.evaluationRunId().equals(result.evaluationRunId())
                        && value.caseKey().equals(result.caseKey())
                        && value.logicalModelType().equals(
                        result.logicalModelType()));
        if (duplicate || cases.putIfAbsent(result.id(), result) != null) {
            throw new IllegalStateException("模型评测案例结果重复");
        }
    }

    @Override
    public synchronized void completeAutomation(
            UUID hospitalId, UUID runId, int passedCount,
            String reportSha256, String reportJson,
            java.time.Instant completedAt) {
        RunData run = requireRun(hospitalId, runId);
        if (!"RUNNING".equals(run.status())) {
            throw new IllegalStateException("模型评测批次状态冲突");
        }
        runs.put(runId, new RunData(
                run.id(), run.hospitalId(), run.startedBy(),
                run.datasetVersion(), run.dataClassification(),
                run.promptVersion(), run.routePolicyVersion(),
                run.idempotencyKey(), run.requestSha256(),
                "WAITING_EXPERT_SCORING", run.caseCount(), passedCount,
                reportSha256, reportJson, run.startedAt(), completedAt,
                run.version() + 1));
    }

    @Override
    public synchronized Optional<RunData> findRun(
            UUID hospitalId, UUID runId) {
        RunData value = runs.get(runId);
        return value != null && value.hospitalId().equals(hospitalId)
                ? Optional.of(value) : Optional.empty();
    }

    @Override
    public synchronized Optional<RunData> findRunByStartIdempotency(
            UUID hospitalId, UUID startedBy, String idempotencyKey) {
        return runs.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.startedBy().equals(startedBy)
                        && value.idempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    @Override
    public synchronized List<RunData> findRuns(UUID hospitalId) {
        return runs.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId))
                .sorted(Comparator.comparing(RunData::startedAt).reversed())
                .toList();
    }

    @Override
    public synchronized List<CaseData> findCaseResults(
            UUID hospitalId, UUID runId) {
        return cases.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.evaluationRunId().equals(runId))
                .sorted(Comparator.comparing(CaseData::caseKey))
                .toList();
    }

    @Override
    public synchronized void saveExpertScore(ExpertScoreData score) {
        boolean duplicate = scores.values().stream().anyMatch(value ->
                (value.hospitalId().equals(score.hospitalId())
                        && value.reviewerId().equals(score.reviewerId())
                        && value.idempotencyKey().equals(
                        score.idempotencyKey()))
                        || (value.evaluationRunId().equals(
                        score.evaluationRunId())
                        && (value.responsibility().equals(
                        score.responsibility())
                        || value.reviewerId().equals(
                        score.reviewerId()))));
        if (duplicate || scores.putIfAbsent(score.id(), score) != null) {
            throw new IllegalStateException("评测必须由两名不同专家分别评分");
        }
    }

    @Override
    public synchronized Optional<ExpertScoreData>
    findExpertScoreByIdempotency(
            UUID hospitalId, UUID reviewerId, String idempotencyKey) {
        return scores.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.reviewerId().equals(reviewerId)
                        && value.idempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    @Override
    public synchronized List<ExpertScoreData> findExpertScores(
            UUID hospitalId, UUID runId) {
        return scores.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.evaluationRunId().equals(runId))
                .sorted(Comparator.comparing(
                        ExpertScoreData::responsibility))
                .toList();
    }

    @Override
    public synchronized void markCompleted(UUID hospitalId, UUID runId) {
        RunData run = requireRun(hospitalId, runId);
        if (!"WAITING_EXPERT_SCORING".equals(run.status())) {
            throw new IllegalStateException("模型评测批次状态冲突");
        }
        runs.put(runId, new RunData(
                run.id(), run.hospitalId(), run.startedBy(),
                run.datasetVersion(), run.dataClassification(),
                run.promptVersion(), run.routePolicyVersion(),
                run.idempotencyKey(), run.requestSha256(),
                "COMPLETED", run.caseCount(), run.passedCount(),
                run.reportSha256(), run.reportJson(), run.startedAt(),
                run.completedAt(), run.version() + 1));
    }

    private RunData requireRun(UUID hospitalId, UUID runId) {
        return findRun(hospitalId, runId)
                .orElseThrow(() -> new IllegalStateException(
                        "模型评测批次不存在"));
    }
}
