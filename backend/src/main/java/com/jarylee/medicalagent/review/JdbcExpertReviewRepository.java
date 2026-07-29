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
    public ReviewTaskData create(ReviewTaskData task) {
        jdbc.sql("""
                insert into research_review_task(
                    id,hospital_id,project_id,agent_task_id,protocol_id,
                    strobe_check_task_id,status,submitted_by,submitted_at,
                    sections_locked,version,created_at,updated_at
                ) values(
                    :id,:hospitalId,:projectId,:agentTaskId,:protocolId,
                    :strobeCheckTaskId,:status,:submittedBy,:submittedAt,
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
                .update();
        return findByAgentTask(task.hospitalId(), task.agentTaskId()).orElseThrow();
    }

    @Override
    public ReviewCommentData addComment(ReviewCommentData comment) {
        jdbc.sql("""
                insert into research_review_comment(
                    id,hospital_id,review_task_id,protocol_section_id,
                    protocol_section_version_no,strobe_item_result_id,
                    comment_type,content,created_by,created_at
                ) values(
                    :id,:hospitalId,:reviewTaskId,:protocolSectionId,
                    :protocolSectionVersionNo,:strobeItemResultId,
                    :commentType,:content,:createdBy,:createdAt
                )
                """)
                .param("id", comment.id())
                .param("hospitalId", comment.hospitalId())
                .param("reviewTaskId", comment.reviewTaskId())
                .param("protocolSectionId", comment.protocolSectionId())
                .param("protocolSectionVersionNo", comment.protocolSectionVersionNo())
                .param("strobeItemResultId", comment.strobeItemResultId())
                .param("commentType", comment.commentType())
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
                    comment_type,content,created_by,created_at
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
                    actor_user_id,summary,occurred_at
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
                        result.getObject("actor_user_id", UUID.class),
                        result.getString("summary"),
                        result.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    @Override
    @Transactional
    public Optional<ReviewTaskData> decide(
            UUID hospitalId, UUID reviewTaskId, UUID reviewerId, String decision,
            String summary, java.time.Instant decidedAt, long expectedVersion) {
        String status = "APPROVE".equals(decision)
                ? "EXPERT_APPROVED" : "REVISION_REQUIRED";
        int updated = jdbc.sql("""
                update research_review_task
                set status=:status,expert_reviewer_id=:reviewerId,
                    expert_decision=:decision,expert_summary=:summary,
                    expert_decided_at=:decidedAt,version=version+1,
                    updated_at=:decidedAt
                where hospital_id=:hospitalId and id=:reviewTaskId
                  and status='WAITING_EXPERT_REVIEW' and version=:expectedVersion
                """)
                .param("status", status)
                .param("reviewerId", reviewerId)
                .param("decision", decision)
                .param("summary", summary)
                .param("decidedAt", Timestamp.from(decidedAt))
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
            java.time.Instant confirmedAt, long expectedVersion) {
        int updated = jdbc.sql("""
                update research_review_task
                set status='APPROVED',owner_confirmed_by=:ownerId,
                    owner_confirmed_at=:confirmedAt,sections_locked=true,
                    version=version+1,updated_at=:confirmedAt
                where hospital_id=:hospitalId and id=:reviewTaskId
                  and status='EXPERT_APPROVED'
                  and expert_decision='APPROVE' and version=:expectedVersion
                """)
                .param("ownerId", ownerId)
                .param("confirmedAt", Timestamp.from(confirmedAt))
                .param("hospitalId", hospitalId)
                .param("reviewTaskId", reviewTaskId)
                .param("expectedVersion", expectedVersion)
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
                    actor_user_id,summary,occurred_at
                ) values(
                    :id,:hospitalId,:reviewTaskId,:actionType,
                    :actorUserId,:summary,:occurredAt
                )
                """)
                .param("id", action.id())
                .param("hospitalId", action.hospitalId())
                .param("reviewTaskId", action.reviewTaskId())
                .param("actionType", action.actionType())
                .param("actorUserId", action.actorUserId())
                .param("summary", action.summary())
                .param("occurredAt", Timestamp.from(action.occurredAt()))
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
                result.getObject("submitted_by", UUID.class),
                result.getTimestamp("submitted_at").toInstant(),
                result.getObject("expert_reviewer_id", UUID.class),
                result.getString("expert_decision"),
                result.getString("expert_summary"),
                result.getTimestamp("expert_decided_at") == null ? null
                        : result.getTimestamp("expert_decided_at").toInstant(),
                result.getObject("owner_confirmed_by", UUID.class),
                result.getTimestamp("owner_confirmed_at") == null ? null
                        : result.getTimestamp("owner_confirmed_at").toInstant(),
                result.getBoolean("sections_locked"),
                result.getLong("version"));
    }
}
