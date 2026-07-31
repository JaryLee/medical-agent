package com.jarylee.medicalagent.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.ProtocolModelGovernanceRepository;
import com.jarylee.medicalagent.workflow.AgentEventStream;
import com.jarylee.medicalagent.workflow.AgentWorkflowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;

@Service
public class WorkspaceModelGovernancePersistenceService {
    private final ProtocolModelGovernanceRepository repository;
    private final AgentWorkflowRepository workflows;
    private final AgentEventStream events;
    private final ObjectMapper json;

    public WorkspaceModelGovernancePersistenceService(
            ProtocolModelGovernanceRepository repository,
            AgentWorkflowRepository workflows,
            AgentEventStream events,
            ObjectMapper json) {
        this.repository = repository;
        this.workflows = workflows;
        this.events = events;
        this.json = json;
    }

    @Transactional
    public void saveCandidate(
            ProtocolModelGovernanceRepository.CandidateData candidate) {
        repository.saveCandidate(candidate);
        var event = workflows.appendEvent(
                candidate.hospitalId(),
                candidate.agentTaskId(),
                "PROTOCOL_MODEL_CANDIDATE_GENERATED",
                "STEP_17_WAIT_EXPERT_REVIEW",
                write(Map.of(
                        "sectionCode", candidate.sectionCode(),
                        "baseVersionNo", candidate.baseVersionNo(),
                        "validationStatus", candidate.status())),
                candidate.generatedAt());
        publishAfterCommit(event);
    }

    @Transactional
    public void saveReview(
            ProtocolModelGovernanceRepository.CandidateData candidate,
            ProtocolModelGovernanceRepository.ReviewData review) {
        repository.saveReview(review);
        var event = workflows.appendEvent(
                candidate.hospitalId(),
                candidate.agentTaskId(),
                "PROTOCOL_MODEL_REVIEW_COMPLETED",
                "STEP_17_WAIT_EXPERT_REVIEW",
                write(Map.of(
                        "sectionCode", candidate.sectionCode(),
                        "severity", review.severity(),
                        "advisoryOnly", true)),
                review.createdAt());
        publishAfterCommit(event);
    }

    @Transactional
    public void saveDesignAdvice(
            ProtocolModelGovernanceRepository.DesignAdviceData advice) {
        repository.saveDesignAdvice(advice);
        var event = workflows.appendEvent(
                advice.hospitalId(),
                advice.agentTaskId(),
                "OBSERVATIONAL_DESIGN_MODEL_ADVICE_COMPLETED",
                "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN",
                write(Map.of(
                        "status", advice.status(),
                        "conflictCount", advice.conflictCount(),
                        "advisoryOnly", true)),
                advice.createdAt());
        publishAfterCommit(event);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("模型治理事件序列化失败", exception);
        }
    }

    private void publishAfterCommit(AgentWorkflowRepository.EventData event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            events.publish(event);
                        }
                    });
        } else {
            events.publish(event);
        }
    }
}
