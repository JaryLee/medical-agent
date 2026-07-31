package com.jarylee.medicalagent.agent;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("memory")
public class MemoryProtocolModelGovernanceRepository
        implements ProtocolModelGovernanceRepository {
    private final Map<UUID, CandidateData> candidates = new LinkedHashMap<>();
    private final Map<UUID, ReviewData> reviews = new LinkedHashMap<>();
    private final Map<UUID, DesignAdviceData> designAdvice =
            new LinkedHashMap<>();

    @Override
    public synchronized void saveCandidate(CandidateData candidate) {
        if (candidates.containsKey(candidate.id())
                || candidates.values().stream().anyMatch(existing ->
                existing.hospitalId().equals(candidate.hospitalId())
                        && existing.modelCallId() != null
                        && existing.modelCallId().equals(candidate.modelCallId()))) {
            throw new IllegalStateException("模型章节候选重复");
        }
        candidates.put(candidate.id(), candidate);
    }

    @Override
    public synchronized Optional<CandidateData> findCandidate(
            UUID hospitalId, UUID candidateId) {
        CandidateData value = candidates.get(candidateId);
        return value != null && value.hospitalId().equals(hospitalId)
                ? Optional.of(value) : Optional.empty();
    }

    @Override
    public synchronized List<CandidateData> findCandidates(
            UUID hospitalId, UUID projectId) {
        return candidates.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.projectId().equals(projectId))
                .sorted(java.util.Comparator.comparing(
                        CandidateData::generatedAt).reversed())
                .toList();
    }

    @Override
    public synchronized boolean markCandidateApplied(
            UUID hospitalId,
            UUID candidateId,
            long expectedVersion,
            UUID appliedBy,
            Instant appliedAt,
            int appliedVersionNo) {
        CandidateData current = candidates.get(candidateId);
        if (current == null || !current.hospitalId().equals(hospitalId)
                || !"VALIDATED".equals(current.status())
                || current.version() != expectedVersion) {
            return false;
        }
        candidates.put(candidateId, new CandidateData(
                current.id(), current.hospitalId(), current.projectId(),
                current.agentTaskId(), current.protocolId(), current.sectionId(),
                current.sectionCode(), current.baseVersionNo(), current.modelCallId(),
                current.promptVersion(), current.content(), current.contentSha256(),
                current.usedEvidenceKeysJson(), current.allowedEvidenceSha256(),
                current.issuesToConfirmJson(), current.validationJson(),
                "APPLIED", current.generatedAt(), appliedBy, appliedAt,
                appliedVersionNo, current.version() + 1));
        return true;
    }

    @Override
    public synchronized void saveReview(ReviewData review) {
        if (reviews.values().stream().anyMatch(existing ->
                existing.hospitalId().equals(review.hospitalId())
                        && (existing.candidateId().equals(review.candidateId())
                        || existing.modelCallId().equals(review.modelCallId())))) {
            throw new IllegalStateException("模型章节复核重复");
        }
        reviews.put(review.id(), review);
    }

    @Override
    public synchronized Optional<ReviewData> findReviewByCandidate(
            UUID hospitalId, UUID candidateId) {
        return reviews.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.candidateId().equals(candidateId))
                .findFirst();
    }

    @Override
    public synchronized List<ReviewData> findReviews(
            UUID hospitalId, UUID projectId) {
        return reviews.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.projectId().equals(projectId))
                .sorted(java.util.Comparator.comparing(
                        ReviewData::createdAt).reversed())
                .toList();
    }

    @Override
    public synchronized void saveDesignAdvice(DesignAdviceData advice) {
        if (designAdvice.containsKey(advice.id())
                || designAdvice.values().stream().anyMatch(existing ->
                existing.hospitalId().equals(advice.hospitalId())
                        && existing.modelCallId() != null
                        && existing.modelCallId().equals(advice.modelCallId()))) {
            throw new IllegalStateException("观察性研究设计模型建议重复");
        }
        designAdvice.put(advice.id(), advice);
    }

    @Override
    public synchronized List<DesignAdviceData> findDesignAdvice(
            UUID hospitalId, UUID projectId) {
        return designAdvice.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.projectId().equals(projectId))
                .sorted(java.util.Comparator.comparing(
                        DesignAdviceData::createdAt).reversed())
                .toList();
    }
}
