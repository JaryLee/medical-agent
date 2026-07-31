package com.jarylee.medicalagent.agent.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModelEvaluationRepository {
    void createRun(RunData run);

    void saveCaseResult(CaseData result);

    void completeAutomation(
            UUID hospitalId,
            UUID runId,
            int passedCount,
            String reportSha256,
            String reportJson,
            Instant completedAt);

    Optional<RunData> findRun(UUID hospitalId, UUID runId);

    Optional<RunData> findRunByStartIdempotency(
            UUID hospitalId, UUID startedBy, String idempotencyKey);

    List<RunData> findRuns(UUID hospitalId);

    List<CaseData> findCaseResults(UUID hospitalId, UUID runId);

    void saveExpertScore(ExpertScoreData score);

    Optional<ExpertScoreData> findExpertScoreByIdempotency(
            UUID hospitalId, UUID reviewerId, String idempotencyKey);

    List<ExpertScoreData> findExpertScores(UUID hospitalId, UUID runId);

    void markCompleted(UUID hospitalId, UUID runId);

    record RunData(
            UUID id,
            UUID hospitalId,
            UUID startedBy,
            String datasetVersion,
            String dataClassification,
            String promptVersion,
            String routePolicyVersion,
            String idempotencyKey,
            String requestSha256,
            String status,
            int caseCount,
            Integer passedCount,
            String reportSha256,
            String reportJson,
            Instant startedAt,
            Instant completedAt,
            long version) {}

    record CaseData(
            UUID id,
            UUID hospitalId,
            UUID evaluationRunId,
            String caseKey,
            String logicalModelType,
            String provider,
            String modelName,
            String outputSha256,
            boolean passed,
            String metricsJson,
            String errorCode,
            Instant evaluatedAt) {}

    record ExpertScoreData(
            UUID id,
            UUID hospitalId,
            UUID evaluationRunId,
            String responsibility,
            UUID reviewerId,
            short correctnessScore,
            short completenessScore,
            short safetyScore,
            short actionabilityScore,
            String recommendation,
            String comment,
            String idempotencyKey,
            String requestSha256,
            Instant submittedAt) {}
}
