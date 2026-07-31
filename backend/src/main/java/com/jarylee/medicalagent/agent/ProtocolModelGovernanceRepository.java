package com.jarylee.medicalagent.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProtocolModelGovernanceRepository {
    void saveCandidate(CandidateData candidate);

    Optional<CandidateData> findCandidate(UUID hospitalId, UUID candidateId);

    List<CandidateData> findCandidates(UUID hospitalId, UUID projectId);

    boolean markCandidateApplied(
            UUID hospitalId,
            UUID candidateId,
            long expectedVersion,
            UUID appliedBy,
            Instant appliedAt,
            int appliedVersionNo);

    void saveReview(ReviewData review);

    Optional<ReviewData> findReviewByCandidate(
            UUID hospitalId, UUID candidateId);

    List<ReviewData> findReviews(UUID hospitalId, UUID projectId);

    void saveDesignAdvice(DesignAdviceData advice);

    List<DesignAdviceData> findDesignAdvice(UUID hospitalId, UUID projectId);

    record CandidateData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            UUID protocolId,
            UUID sectionId,
            String sectionCode,
            int baseVersionNo,
            UUID modelCallId,
            String promptVersion,
            String content,
            String contentSha256,
            String usedEvidenceKeysJson,
            String allowedEvidenceSha256,
            String issuesToConfirmJson,
            String validationJson,
            String status,
            Instant generatedAt,
            UUID appliedBy,
            Instant appliedAt,
            Integer appliedVersionNo,
            long version) {}

    record ReviewData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID candidateId,
            UUID modelCallId,
            String candidateContentSha256,
            String severity,
            String issuesJson,
            String summary,
            boolean advisoryOnly,
            Instant createdAt) {}

    record DesignAdviceData(
            UUID id,
            UUID hospitalId,
            UUID projectId,
            UUID agentTaskId,
            UUID modelCallId,
            String ruleVersion,
            String promptVersion,
            String ruleRecommendedStudyType,
            String modelSelectedStudyType,
            String adviceJson,
            String adviceSha256,
            String conflictsJson,
            int conflictCount,
            String status,
            boolean advisoryOnly,
            Instant createdAt) {}
}
