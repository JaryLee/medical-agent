package com.jarylee.medicalagent.review;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcExpertReviewRepository implements ExpertReviewRepository {
    private final JdbcClient jdbc;

    public JdbcExpertReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ReviewTaskData> findByAgentTask(
            UUID hospitalId, UUID agentTaskId) {
        return jdbc.sql("""
                select *
                from research_review_task
                where hospital_id=:hospitalId and agent_task_id=:agentTaskId
                """)
                .param("hospitalId", hospitalId)
                .param("agentTaskId", agentTaskId)
                .query(this::mapTask)
                .optional();
    }

    @Override
    public Optional<ReviewTaskData> findLatestByProject(
            UUID hospitalId, UUID projectId) {
        return jdbc.sql("""
                select *
                from research_review_task
                where hospital_id=:hospitalId
                  and project_id=:projectId
                order by round_no desc,created_at desc,id desc
                limit 1
                """)
                .param("hospitalId", hospitalId)
                .param("projectId", projectId)
                .query(this::mapTask)
                .optional();
    }

    @Override
    public ReviewTaskData create(ReviewTaskData task) {
        jdbc.sql("""
                insert into research_review_task(
                    id,hospital_id,project_id,agent_task_id,protocol_id,
                    strobe_check_task_id,status,submitted_by,submitted_at,
                    round_no,review_content_sha256,legacy_review,
                    sections_locked,version,created_at,updated_at
                ) values(
                    :id,:hospitalId,:projectId,:agentTaskId,:protocolId,
                    :strobeCheckTaskId,:status,:submittedBy,:submittedAt,
                    :roundNo,:contentSha256,false,
                    false,0,:submittedAt,:submittedAt
                )
                on conflict (agent_task_id) do nothing
                """)
                .param("id", task.id())
                .param("hospitalId", task.hospitalId())
                .param("projectId", task.projectId())
                .param("agentTaskId", task.agentTaskId())
                .param("protocolId", task.protocolId())
                .param("strobeCheckTaskId", task.strobeCheckTaskId())
                .param("status", task.status())
                .param("submittedBy", task.submittedBy())
                .param("submittedAt", Timestamp.from(task.submittedAt()))
                .param("roundNo", task.roundNo())
                .param("contentSha256", task.contentSha256())
                .update();
        return findByAgentTask(task.hospitalId(), task.agentTaskId()).orElseThrow();
    }

    @Override
    public ReviewCommentData addComment(ReviewCommentData comment) {
        jdbc.sql("""
                insert into research_review_comment(
                    id,hospital_id,review_task_id,protocol_section_id,
                    protocol_section_version_no,strobe_item_result_id,
                    comment_type,responsibility,review_round_no,
                    content,created_by,created_at
                ) values(
                    :id,:hospitalId,:reviewTaskId,:protocolSectionId,
                    :protocolSectionVersionNo,:strobeItemResultId,
                    :commentType,:responsibility,:reviewRoundNo,
                    :content,:createdBy,:createdAt
                )
                """)
                .param("id", comment.id())
                .param("hospitalId", comment.hospitalId())
                .param("reviewTaskId", comment.reviewTaskId())
                .param("protocolSectionId", comment.protocolSectionId())
                .param("protocolSectionVersionNo", comment.protocolSectionVersionNo())
                .param("strobeItemResultId", comment.strobeItemResultId())
                .param("commentType", comment.commentType())
                .param("responsibility", comment.responsibility())
                .param("reviewRoundNo", comment.reviewRoundNo())
                .param("content", comment.content())
                .param("createdBy", comment.createdBy())
                .param("createdAt", Timestamp.from(comment.createdAt()))
                .update();
        return comment;
    }

    @Override
    public List<ReviewCommentData> findComments(
            UUID hospitalId, UUID reviewTaskId) {
        return jdbc.sql("""
                select id,hospital_id,review_task_id,protocol_section_id,
                    protocol_section_version_no,strobe_item_result_id,
                    comment_type,responsibility,review_round_no,
                    content,created_by,created_at
                from research_review_comment
                where hospital_id=:hospitalId and review_task_id=:reviewTaskId
                order by created_at,id
                """)
                .param("hospitalId", hospitalId)
                .param("reviewTaskId", reviewTaskId)
                .query((result, row) -> new ReviewCommentData(
                        result.getObject("id", UUID.class),
                        result.getObject("hospital_id", UUID.class),
                        result.getObject("review_task_id", UUID.class),
                        result.getObject("protocol_section_id", UUID.class),
                        (Integer) result.getObject("protocol_section_version_no"),
                        result.getObject("strobe_item_result_id", UUID.class),
                        result.getString("comment_type"),
                        result.getString("responsibility"),
                        result.getInt("review_round_no"),
                        result.getString("content"),
                        result.getObject("created_by", UUID.class),
                        result.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public List<ReviewActionData> findActions(
            UUID hospitalId, UUID reviewTaskId) {
        return jdbc.sql("""
                select id,hospital_id,review_task_id,action_type,
                    review_round_no,actor_user_id,summary,occurred_at
                from research_review_action
                where hospital_id=:hospitalId and review_task_id=:reviewTaskId
                order by occurred_at,id
                """)
                .param("hospitalId", hospitalId)
                .param("reviewTaskId", reviewTaskId)
                .query((result, row) -> new ReviewActionData(
                        result.getObject("id", UUID.class),
                        result.getObject("hospital_id", UUID.class),
                        result.getObject("review_task_id", UUID.class),
                        result.getString("action_type"),
                        result.getInt("review_round_no"),
                        result.getObject("actor_user_id", UUID.class),
                        result.getString("summary"),
                        result.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    @Override
    @Transactional
    public Optional<ReviewTaskData> decide(
            UUID hospitalId, UUID reviewTaskId, String responsibility,
            UUID reviewerId, String decision, String summary,
            String contentSha256, java.time.Instant decidedAt,
            long expectedVersion) {
        boolean medical = "MEDICAL_REVIEW".equals(responsibility);
        String reviewerColumn = medical
                ? "expert_reviewer_id" : "statistical_reviewer_id";
        String decisionColumn = medical
                ? "expert_decision" : "statistical_decision";
        String summaryColumn = medical
                ? "expert_summary" : "statistical_summary";
        String decidedAtColumn = medical
                ? "expert_decided_at" : "statistical_decided_at";
        String otherReviewerColumn = medical
                ? "statistical_reviewer_id" : "expert_reviewer_id";
        String otherDecisionColumn = medical
                ? "statistical_decision" : "expert_decision";
        String sql = """
                update research_review_task
                set status=case
                        when :decision='RETURN_FOR_REVISION'
                            then 'REVISION_REQUIRED'
                        when %s='APPROVE' then 'EXPERT_APPROVED'
                        else 'WAITING_EXPERT_REVIEW'
                    end,
                    %s=:reviewerId,
                    %s=:decision,
                    %s=:summary,
                    %s=:decidedAt,
                    version=version+1,
                    updated_at=:decidedAt
                where hospital_id=:hospitalId and id=:reviewTaskId
                  and status='WAITING_EXPERT_REVIEW'
                  and review_content_sha256=:contentSha256
                  and (%s is null or %s=:reviewerId)
                  and %s is null
                  and (%s is null or %s<>:reviewerId)
                  and submitted_by<>:reviewerId
                  and version=:expectedVersion
                """.formatted(
                otherDecisionColumn, reviewerColumn, decisionColumn,
                summaryColumn, decidedAtColumn,
                reviewerColumn, reviewerColumn,
                decisionColumn,
                otherReviewerColumn, otherReviewerColumn);
        int updated = jdbc.sql(sql)
                .param("reviewerId", reviewerId)
                .param("decision", decision)
                .param("summary", summary)
                .param("decidedAt", Timestamp.from(decidedAt))
                .param("contentSha256", contentSha256)
                .param("hospitalId", hospitalId)
                .param("reviewTaskId", reviewTaskId)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1 ? findById(hospitalId, reviewTaskId) : Optional.empty();
    }

    @Override
    @Transactional
    public Optional<ReviewTaskData> ownerConfirmAndLock(
            UUID hospitalId, UUID reviewTaskId, UUID ownerId,
            String contentSha256, java.time.Instant confirmedAt,
            long expectedVersion) {
        int updated = jdbc.sql("""
                update research_review_task
                set status='APPROVED',owner_confirmed_by=:ownerId,
                    owner_confirmed_at=:confirmedAt,sections_locked=true,
                    version=version+1,updated_at=:confirmedAt
                where hospital_id=:hospitalId and id=:reviewTaskId
                  and status='EXPERT_APPROVED'
                  and expert_decision='APPROVE' and version=:expectedVersion
                  and statistical_decision='APPROVE'
                  and review_content_sha256=:contentSha256
                  and expert_reviewer_id<>:ownerId
                  and statistical_reviewer_id<>:ownerId
                """)
                .param("ownerId", ownerId)
                .param("confirmedAt", Timestamp.from(confirmedAt))
                .param("hospitalId", hospitalId)
                .param("reviewTaskId", reviewTaskId)
                .param("expectedVersion", expectedVersion)
                .param("contentSha256", contentSha256)
                .update();
        if (updated != 1) return Optional.empty();
        ReviewTaskData task = findById(hospitalId, reviewTaskId).orElseThrow();
        jdbc.sql("""
                update research_protocol
                set status='APPROVED',version=version+1,updated_at=:confirmedAt
                where hospital_id=:hospitalId and id=:protocolId
                  and status in ('DRAFT','WAITING_REVIEW')
                """)
                .param("confirmedAt", Timestamp.from(confirmedAt))
                .param("hospitalId", hospitalId)
                .param("protocolId", task.protocolId())
                .update();
        jdbc.sql("""
                update research_protocol_section
                set status='LOCKED',version=version+1,updated_at=:confirmedAt
                where hospital_id=:hospitalId and protocol_id=:protocolId
                  and status='DRAFT'
                """)
                .param("confirmedAt", Timestamp.from(confirmedAt))
                .param("hospitalId", hospitalId)
                .param("protocolId", task.protocolId())
                .update();
        return Optional.of(task);
    }

    @Override
    public void addAction(ReviewActionData action) {
        jdbc.sql("""
                insert into research_review_action(
                    id,hospital_id,review_task_id,action_type,
                    review_round_no,actor_user_id,summary,occurred_at
                ) values(
                    :id,:hospitalId,:reviewTaskId,:actionType,
                    :reviewRoundNo,:actorUserId,:summary,:occurredAt
                )
                """)
                .param("id", action.id())
                .param("hospitalId", action.hospitalId())
                .param("reviewTaskId", action.reviewTaskId())
                .param("actionType", action.actionType())
                .param("reviewRoundNo", action.reviewRoundNo())
                .param("actorUserId", action.actorUserId())
                .param("summary", action.summary())
                .param("occurredAt", Timestamp.from(action.occurredAt()))
                .update();
    }

    @Override
    public Optional<ReviewTaskData> resetForNewRound(
            UUID hospitalId, UUID reviewTaskId, String contentSha256,
            java.time.Instant submittedAt, long expectedVersion) {
        int updated = jdbc.sql("""
                update research_review_task
                set status='WAITING_EXPERT_REVIEW',
                    round_no=round_no+1,
                    review_content_sha256=:contentSha256,
                    legacy_review=false,
                    submitted_at=:submittedAt,
                    expert_reviewer_id=null,
                    expert_decision=null,
                    expert_summary=null,
                    expert_decided_at=null,
                    statistical_reviewer_id=null,
                    statistical_decision=null,
                    statistical_summary=null,
                    statistical_decided_at=null,
                    owner_confirmed_by=null,
                    owner_confirmed_at=null,
                    sections_locked=false,
                    version=version+1,
                    updated_at=:submittedAt
                where hospital_id=:hospitalId and id=:reviewTaskId
                  and version=:expectedVersion
                  and (
                    status='SUPERSEDED'
                    or review_content_sha256<>:contentSha256
                  )
                """)
                .param("contentSha256", contentSha256)
                .param("submittedAt", Timestamp.from(submittedAt))
                .param("hospitalId", hospitalId)
                .param("reviewTaskId", reviewTaskId)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1
                ? findById(hospitalId, reviewTaskId)
                : Optional.empty();
    }

    @Override
    public void addDecision(ReviewDecisionData decision) {
        jdbc.sql("""
                insert into research_review_decision(
                    id,hospital_id,review_task_id,review_round_no,
                    responsibility,reviewer_id,decision,summary,
                    content_sha256,decided_at
                ) values(
                    :id,:hospitalId,:reviewTaskId,:reviewRoundNo,
                    :responsibility,:reviewerId,:decision,:summary,
                    :contentSha256,:decidedAt
                )
                """)
                .param("id", decision.id())
                .param("hospitalId", decision.hospitalId())
                .param("reviewTaskId", decision.reviewTaskId())
                .param("reviewRoundNo", decision.reviewRoundNo())
                .param("responsibility", decision.responsibility())
                .param("reviewerId", decision.reviewerId())
                .param("decision", decision.decision())
                .param("summary", decision.summary())
                .param("contentSha256", decision.contentSha256())
                .param("decidedAt", Timestamp.from(decision.decidedAt()))
                .update();
    }

    private Optional<ReviewTaskData> findById(UUID hospitalId, UUID reviewTaskId) {
        return jdbc.sql("""
                select *
                from research_review_task
                where hospital_id=:hospitalId and id=:reviewTaskId
                """)
                .param("hospitalId", hospitalId)
                .param("reviewTaskId", reviewTaskId)
                .query(this::mapTask)
                .optional();
    }

    private ReviewTaskData mapTask(ResultSet result, int row) throws SQLException {
        return new ReviewTaskData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getObject("agent_task_id", UUID.class),
                result.getObject("protocol_id", UUID.class),
                result.getObject("strobe_check_task_id", UUID.class),
                result.getString("status"),
                result.getInt("round_no"),
                result.getString("review_content_sha256"),
                result.getBoolean("legacy_review"),
                result.getObject("submitted_by", UUID.class),
                result.getTimestamp("submitted_at").toInstant(),
                result.getObject("expert_reviewer_id", UUID.class),
                result.getString("expert_decision"),
                result.getString("expert_summary"),
                result.getTimestamp("expert_decided_at") == null ? null
                        : result.getTimestamp("expert_decided_at").toInstant(),
                result.getObject("statistical_reviewer_id", UUID.class),
                result.getString("statistical_decision"),
                result.getString("statistical_summary"),
                result.getTimestamp("statistical_decided_at") == null ? null
                        : result.getTimestamp("statistical_decided_at").toInstant(),
                result.getObject("owner_confirmed_by", UUID.class),
                result.getTimestamp("owner_confirmed_at") == null ? null
                        : result.getTimestamp("owner_confirmed_at").toInstant(),
                result.getBoolean("sections_locked"),
                result.getLong("version"));
    }
}
