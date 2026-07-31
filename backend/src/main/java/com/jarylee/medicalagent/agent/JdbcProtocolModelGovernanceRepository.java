package com.jarylee.medicalagent.agent;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcProtocolModelGovernanceRepository
        implements ProtocolModelGovernanceRepository {
    private static final String CANDIDATE_COLUMNS = """
            id,hospital_id,project_id,agent_task_id,protocol_id,section_id,
            section_code,base_version_no,model_call_id,prompt_version,content,
            content_sha256,used_evidence_keys_json::text,allowed_evidence_sha256,
            issues_to_confirm_json::text,validation_json::text,status,generated_at,
            applied_by,applied_at,applied_version_no,version
            """;
    private static final String REVIEW_COLUMNS = """
            id,hospital_id,project_id,candidate_id,model_call_id,
            candidate_content_sha256,severity,issues_json::text,summary,
            advisory_only,created_at
            """;
    private static final String DESIGN_ADVICE_COLUMNS = """
            id,hospital_id,project_id,agent_task_id,model_call_id,rule_version,
            prompt_version,rule_recommended_study_type,model_selected_study_type,
            advice_json::text,advice_sha256,conflicts_json::text,conflict_count,
            status,advisory_only,created_at
            """;

    private final JdbcClient jdbc;

    public JdbcProtocolModelGovernanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveCandidate(CandidateData value) {
        jdbc.sql("""
                insert into protocol_section_model_candidate(
                    id,hospital_id,project_id,agent_task_id,protocol_id,section_id,
                    section_code,base_version_no,model_call_id,prompt_version,content,
                    content_sha256,used_evidence_keys_json,allowed_evidence_sha256,
                    issues_to_confirm_json,validation_json,status,generated_at,
                    applied_by,applied_at,applied_version_no,version
                ) values(
                    :id,:hospitalId,:projectId,:agentTaskId,:protocolId,:sectionId,
                    :sectionCode,:baseVersionNo,:modelCallId,:promptVersion,:content,
                    :contentSha256,cast(:usedEvidenceKeysJson as jsonb),
                    :allowedEvidenceSha256,cast(:issuesToConfirmJson as jsonb),
                    cast(:validationJson as jsonb),:status,:generatedAt,
                    :appliedBy,:appliedAt,:appliedVersionNo,:version
                )
                """)
                .param("id", value.id())
                .param("hospitalId", value.hospitalId())
                .param("projectId", value.projectId())
                .param("agentTaskId", value.agentTaskId())
                .param("protocolId", value.protocolId())
                .param("sectionId", value.sectionId())
                .param("sectionCode", value.sectionCode())
                .param("baseVersionNo", value.baseVersionNo())
                .param("modelCallId", value.modelCallId())
                .param("promptVersion", value.promptVersion())
                .param("content", value.content())
                .param("contentSha256", value.contentSha256())
                .param("usedEvidenceKeysJson", value.usedEvidenceKeysJson())
                .param("allowedEvidenceSha256", value.allowedEvidenceSha256())
                .param("issuesToConfirmJson", value.issuesToConfirmJson())
                .param("validationJson", value.validationJson())
                .param("status", value.status())
                .param("generatedAt", Timestamp.from(value.generatedAt()))
                .param("appliedBy", value.appliedBy())
                .param("appliedAt", timestamp(value.appliedAt()))
                .param("appliedVersionNo", value.appliedVersionNo())
                .param("version", value.version())
                .update();
    }

    @Override
    public Optional<CandidateData> findCandidate(
            UUID hospitalId, UUID candidateId) {
        return jdbc.sql("select " + CANDIDATE_COLUMNS + """
                        from protocol_section_model_candidate
                        where hospital_id=:hospitalId and id=:candidateId
                        """)
                .param("hospitalId", hospitalId)
                .param("candidateId", candidateId)
                .query(this::mapCandidate)
                .optional();
    }

    @Override
    public List<CandidateData> findCandidates(
            UUID hospitalId, UUID projectId) {
        return jdbc.sql("select " + CANDIDATE_COLUMNS + """
                        from protocol_section_model_candidate
                        where hospital_id=:hospitalId and project_id=:projectId
                        order by generated_at desc,id desc
                        """)
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .query(this::mapCandidate)
                .list();
    }

    @Override
    public boolean markCandidateApplied(
            UUID hospitalId,
            UUID candidateId,
            long expectedVersion,
            UUID appliedBy,
            Instant appliedAt,
            int appliedVersionNo) {
        return jdbc.sql("""
                update protocol_section_model_candidate
                set status='APPLIED',applied_by=:appliedBy,applied_at=:appliedAt,
                    applied_version_no=:appliedVersionNo,version=version+1
                where hospital_id=:hospitalId and id=:candidateId
                  and status='VALIDATED' and version=:expectedVersion
                """)
                .param("appliedBy", appliedBy)
                .param("appliedAt", Timestamp.from(appliedAt))
                .param("appliedVersionNo", appliedVersionNo)
                .param("hospitalId", hospitalId)
                .param("candidateId", candidateId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    @Override
    public void saveReview(ReviewData value) {
        jdbc.sql("""
                insert into protocol_section_model_review(
                    id,hospital_id,project_id,candidate_id,model_call_id,
                    candidate_content_sha256,severity,issues_json,summary,
                    advisory_only,created_at
                ) values(
                    :id,:hospitalId,:projectId,:candidateId,:modelCallId,
                    :candidateContentSha256,:severity,cast(:issuesJson as jsonb),
                    :summary,:advisoryOnly,:createdAt
                )
                """)
                .param("id", value.id())
                .param("hospitalId", value.hospitalId())
                .param("projectId", value.projectId())
                .param("candidateId", value.candidateId())
                .param("modelCallId", value.modelCallId())
                .param("candidateContentSha256", value.candidateContentSha256())
                .param("severity", value.severity())
                .param("issuesJson", value.issuesJson())
                .param("summary", value.summary())
                .param("advisoryOnly", value.advisoryOnly())
                .param("createdAt", Timestamp.from(value.createdAt()))
                .update();
    }

    @Override
    public Optional<ReviewData> findReviewByCandidate(
            UUID hospitalId, UUID candidateId) {
        return jdbc.sql("select " + REVIEW_COLUMNS + """
                        from protocol_section_model_review
                        where hospital_id=:hospitalId and candidate_id=:candidateId
                        """)
                .param("hospitalId", hospitalId)
                .param("candidateId", candidateId)
                .query(this::mapReview)
                .optional();
    }

    @Override
    public List<ReviewData> findReviews(UUID hospitalId, UUID projectId) {
        return jdbc.sql("select " + REVIEW_COLUMNS + """
                        from protocol_section_model_review
                        where hospital_id=:hospitalId and project_id=:projectId
                        order by created_at desc,id desc
                        """)
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .query(this::mapReview)
                .list();
    }

    @Override
    public void saveDesignAdvice(DesignAdviceData value) {
        jdbc.sql("""
                insert into observational_design_model_advice(
                    id,hospital_id,project_id,agent_task_id,model_call_id,
                    rule_version,prompt_version,rule_recommended_study_type,
                    model_selected_study_type,advice_json,advice_sha256,
                    conflicts_json,conflict_count,status,advisory_only,created_at
                ) values(
                    :id,:hospitalId,:projectId,:agentTaskId,:modelCallId,
                    :ruleVersion,:promptVersion,:ruleRecommendedStudyType,
                    :modelSelectedStudyType,cast(:adviceJson as jsonb),:adviceSha256,
                    cast(:conflictsJson as jsonb),:conflictCount,:status,
                    :advisoryOnly,:createdAt
                )
                """)
                .param("id", value.id())
                .param("hospitalId", value.hospitalId())
                .param("projectId", value.projectId())
                .param("agentTaskId", value.agentTaskId())
                .param("modelCallId", value.modelCallId())
                .param("ruleVersion", value.ruleVersion())
                .param("promptVersion", value.promptVersion())
                .param("ruleRecommendedStudyType",
                        value.ruleRecommendedStudyType())
                .param("modelSelectedStudyType",
                        value.modelSelectedStudyType())
                .param("adviceJson", value.adviceJson())
                .param("adviceSha256", value.adviceSha256())
                .param("conflictsJson", value.conflictsJson())
                .param("conflictCount", value.conflictCount())
                .param("status", value.status())
                .param("advisoryOnly", value.advisoryOnly())
                .param("createdAt", Timestamp.from(value.createdAt()))
                .update();
    }

    @Override
    public List<DesignAdviceData> findDesignAdvice(
            UUID hospitalId, UUID projectId) {
        return jdbc.sql("select " + DESIGN_ADVICE_COLUMNS + """
                        from observational_design_model_advice
                        where hospital_id=:hospitalId and project_id=:projectId
                        order by created_at desc,id desc
                        """)
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .query(this::mapDesignAdvice)
                .list();
    }

    private CandidateData mapCandidate(ResultSet result, int row) throws SQLException {
        return new CandidateData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getObject("agent_task_id", UUID.class),
                result.getObject("protocol_id", UUID.class),
                result.getObject("section_id", UUID.class),
                result.getString("section_code"),
                result.getInt("base_version_no"),
                result.getObject("model_call_id", UUID.class),
                result.getString("prompt_version"),
                result.getString("content"),
                result.getString("content_sha256"),
                result.getString("used_evidence_keys_json"),
                result.getString("allowed_evidence_sha256"),
                result.getString("issues_to_confirm_json"),
                result.getString("validation_json"),
                result.getString("status"),
                result.getTimestamp("generated_at").toInstant(),
                result.getObject("applied_by", UUID.class),
                instant(result, "applied_at"),
                nullableInteger(result, "applied_version_no"),
                result.getLong("version"));
    }

    private ReviewData mapReview(ResultSet result, int row) throws SQLException {
        return new ReviewData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getObject("candidate_id", UUID.class),
                result.getObject("model_call_id", UUID.class),
                result.getString("candidate_content_sha256"),
                result.getString("severity"),
                result.getString("issues_json"),
                result.getString("summary"),
                result.getBoolean("advisory_only"),
                result.getTimestamp("created_at").toInstant());
    }

    private DesignAdviceData mapDesignAdvice(
            ResultSet result, int row) throws SQLException {
        return new DesignAdviceData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getObject("agent_task_id", UUID.class),
                result.getObject("model_call_id", UUID.class),
                result.getString("rule_version"),
                result.getString("prompt_version"),
                result.getString("rule_recommended_study_type"),
                result.getString("model_selected_study_type"),
                result.getString("advice_json"),
                result.getString("advice_sha256"),
                result.getString("conflicts_json"),
                result.getInt("conflict_count"),
                result.getString("status"),
                result.getBoolean("advisory_only"),
                result.getTimestamp("created_at").toInstant());
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Integer nullableInteger(ResultSet result, String column)
            throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }
}
