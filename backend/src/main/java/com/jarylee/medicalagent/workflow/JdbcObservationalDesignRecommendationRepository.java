package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcObservationalDesignRecommendationRepository
        implements ObservationalDesignRecommendationRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcObservationalDesignRecommendationRepository(
            JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void create(RecommendationData recommendation) {
        jdbc.sql("""
                insert into observational_design_recommendation_task(
                    id,hospital_id,project_id,agent_task_id,status,started_at,
                    input_sha256,algorithm_version
                ) values(
                    :id,:hospitalId,:projectId,:agentTaskId,'RUNNING',:startedAt,
                    :inputSha256,:algorithmVersion
                )
                """)
                .param("id", recommendation.id())
                .param("hospitalId", recommendation.hospitalId())
                .param("projectId", recommendation.projectId())
                .param("agentTaskId", recommendation.agentTaskId())
                .param("startedAt", Timestamp.from(recommendation.startedAt()))
                .param("inputSha256", recommendation.inputSha256())
                .param("algorithmVersion", recommendation.algorithmVersion())
                .update();
    }

    @Override
    @Transactional
    public void complete(
            RecommendationData recommendation,
            List<ObservationalDesignRecommendationModels.DesignAlternative> alternatives) {
        int updated = jdbc.sql("""
                update observational_design_recommendation_task set status='COMPLETED',
                    completed_at=:completedAt,recommended_study_type=:studyType,
                    primary_outcome_candidate=:outcome,
                    ready_for_protocol_draft=:ready,alternative_count=:alternativeCount,
                    unresolved_items_json=cast(:unresolvedItems as jsonb),
                    required_confirmations_json=cast(:requiredConfirmations as jsonb),
                    result_json=cast(:resultJson as jsonb),error_code=null,error_message=null,
                    version=version+1
                where hospital_id=:hospitalId and id=:id and status='RUNNING'
                """)
                .param("completedAt", Timestamp.from(recommendation.completedAt()))
                .param("studyType", recommendation.recommendedStudyType())
                .param("outcome", recommendation.primaryOutcomeCandidate())
                .param("ready", recommendation.readyForProtocolDraft())
                .param("alternativeCount", recommendation.alternativeCount())
                .param("unresolvedItems", recommendation.unresolvedItemsJson())
                .param("requiredConfirmations", recommendation.requiredConfirmationsJson())
                .param("resultJson", recommendation.resultJson())
                .param("hospitalId", recommendation.hospitalId())
                .param("id", recommendation.id())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("观察性研究设计推荐任务当前不可完成");
        }
        for (var alternative : alternatives) {
            jdbc.sql("""
                    insert into observational_design_alternative(
                        id,hospital_id,recommendation_task_id,rank_no,study_type,
                        score,feasibility_status,rationale,required_fields_json,
                        missing_fields_json,bias_risks_json,evidence_considerations_json
                    ) values(
                        :id,:hospitalId,:taskId,:rank,:studyType,:score,:feasibility,
                        :rationale,cast(:requiredFields as jsonb),cast(:missingFields as jsonb),
                        cast(:biasRisks as jsonb),cast(:evidenceConsiderations as jsonb)
                    )
                    """)
                    .param("id", UUID.randomUUID())
                    .param("hospitalId", recommendation.hospitalId())
                    .param("taskId", recommendation.id())
                    .param("rank", alternative.rank())
                    .param("studyType", alternative.studyType().name())
                    .param("score", alternative.score())
                    .param("feasibility", alternative.feasibilityStatus())
                    .param("rationale", alternative.rationale())
                    .param("requiredFields", write(alternative.requiredFields()))
                    .param("missingFields", write(alternative.missingFields()))
                    .param("biasRisks", write(alternative.biasRisks()))
                    .param("evidenceConsiderations", write(
                            alternative.evidenceConsiderations()))
                    .update();
        }
    }

    @Override
    public void fail(
            UUID hospitalId, UUID recommendationId, String errorCode,
            String errorMessage, Instant completedAt) {
        jdbc.sql("""
                update observational_design_recommendation_task set status='FAILED',
                    completed_at=:completedAt,error_code=:errorCode,
                    error_message=:errorMessage,version=version+1
                where hospital_id=:hospitalId and id=:id and status='RUNNING'
                """)
                .param("completedAt", Timestamp.from(completedAt))
                .param("errorCode", errorCode)
                .param("errorMessage", truncate(errorMessage))
                .param("hospitalId", hospitalId)
                .param("id", recommendationId)
                .update();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("观察性研究设计推荐记录序列化失败", exception);
        }
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
