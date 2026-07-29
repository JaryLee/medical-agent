package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcStrobeCompletenessRepository
        implements StrobeCompletenessRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcStrobeCompletenessRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<CheckTaskData> findByAgentTask(
            UUID hospitalId, UUID agentTaskId) {
        return jdbc.sql("""
                select id,hospital_id,project_id,agent_task_id,protocol_id,status,
                    study_type,total_item_count,covered_count,partially_covered_count,
                    missing_count,not_applicable_count,needs_expert_review_count,
                    input_sha256,checker_version,result_json::text,created_at
                from strobe_completeness_check_task
                where hospital_id=:hospitalId and agent_task_id=:agentTaskId
                """)
                .param("hospitalId", hospitalId)
                .param("agentTaskId", agentTaskId)
                .query((result, rowNum) -> new CheckTaskData(
                        result.getObject("id", UUID.class),
                        result.getObject("hospital_id", UUID.class),
                        result.getObject("project_id", UUID.class),
                        result.getObject("agent_task_id", UUID.class),
                        result.getObject("protocol_id", UUID.class),
                        result.getString("status"),
                        result.getString("study_type"),
                        result.getInt("total_item_count"),
                        result.getInt("covered_count"),
                        result.getInt("partially_covered_count"),
                        result.getInt("missing_count"),
                        result.getInt("not_applicable_count"),
                        result.getInt("needs_expert_review_count"),
                        result.getString("input_sha256"),
                        result.getString("checker_version"),
                        result.getString("result_json"),
                        result.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    @Transactional
    public void save(
            CheckTaskData task,
            List<StrobeCompletenessModels.CheckItem> items) {
        jdbc.sql("""
                insert into strobe_completeness_check_task(
                    id,hospital_id,project_id,agent_task_id,protocol_id,status,
                    study_type,total_item_count,covered_count,partially_covered_count,
                    missing_count,not_applicable_count,needs_expert_review_count,
                    input_sha256,checker_version,result_json,created_at
                ) values(
                    :id,:hospitalId,:projectId,:agentTaskId,:protocolId,:status,
                    :studyType,:totalItemCount,:coveredCount,:partiallyCoveredCount,
                    :missingCount,:notApplicableCount,:needsExpertReviewCount,
                    :inputSha256,:checkerVersion,cast(:resultJson as jsonb),:createdAt
                )
                """)
                .param("id", task.id())
                .param("hospitalId", task.hospitalId())
                .param("projectId", task.projectId())
                .param("agentTaskId", task.agentTaskId())
                .param("protocolId", task.protocolId())
                .param("status", task.status())
                .param("studyType", task.studyType())
                .param("totalItemCount", task.totalItemCount())
                .param("coveredCount", task.coveredCount())
                .param("partiallyCoveredCount", task.partiallyCoveredCount())
                .param("missingCount", task.missingCount())
                .param("notApplicableCount", task.notApplicableCount())
                .param("needsExpertReviewCount", task.needsExpertReviewCount())
                .param("inputSha256", task.inputSha256())
                .param("checkerVersion", task.checkerVersion())
                .param("resultJson", task.resultJson())
                .param("createdAt", Timestamp.from(task.createdAt()))
                .update();
        for (var item : items) {
            jdbc.sql("""
                    insert into strobe_completeness_check_item(
                        id,hospital_id,check_task_id,item_code,section_group,
                        requirement_summary,study_type,check_status,
                        mapped_section_codes_json,evidence_snippets_json,
                        message,suggestion,requires_expert_review,created_at
                    ) values(
                        :id,:hospitalId,:checkTaskId,:itemCode,:sectionGroup,
                        :requirementSummary,:studyType,:checkStatus,
                        cast(:mappedSections as jsonb),cast(:evidenceSnippets as jsonb),
                        :message,:suggestion,:requiresExpertReview,:createdAt
                    )
                    """)
                    .param("id", item.itemResultId())
                    .param("hospitalId", task.hospitalId())
                    .param("checkTaskId", task.id())
                    .param("itemCode", item.itemCode())
                    .param("sectionGroup", item.sectionGroup())
                    .param("requirementSummary", item.requirementSummary())
                    .param("studyType", item.studyType().name())
                    .param("checkStatus", item.status())
                    .param("mappedSections", write(item.mappedSectionCodes()))
                    .param("evidenceSnippets", write(item.evidenceSnippets()))
                    .param("message", item.message())
                    .param("suggestion", item.suggestion())
                    .param("requiresExpertReview", item.requiresExpertReview())
                    .param("createdAt", Timestamp.from(task.createdAt()))
                    .update();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("STROBE 预检查条目序列化失败", exception);
        }
    }
}
