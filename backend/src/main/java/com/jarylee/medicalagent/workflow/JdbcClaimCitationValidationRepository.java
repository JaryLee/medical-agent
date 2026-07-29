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
public class JdbcClaimCitationValidationRepository
        implements ClaimCitationValidationRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcClaimCitationValidationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<ValidationTaskData> findByAgentTask(
            UUID hospitalId, UUID agentTaskId) {
        return jdbc.sql("""
                select id,hospital_id,project_id,agent_task_id,protocol_id,status,
                    claim_count,citation_link_count,abstract_only_claim_count,
                    needs_expert_review_claim_count,input_sha256,validator_version,
                    result_json::text,created_at
                from claim_citation_validation_task
                where hospital_id=:hospitalId and agent_task_id=:agentTaskId
                """)
                .param("hospitalId", hospitalId)
                .param("agentTaskId", agentTaskId)
                .query((result, rowNum) -> new ValidationTaskData(
                        result.getObject("id", UUID.class),
                        result.getObject("hospital_id", UUID.class),
                        result.getObject("project_id", UUID.class),
                        result.getObject("agent_task_id", UUID.class),
                        result.getObject("protocol_id", UUID.class),
                        result.getString("status"),
                        result.getInt("claim_count"),
                        result.getInt("citation_link_count"),
                        result.getInt("abstract_only_claim_count"),
                        result.getInt("needs_expert_review_claim_count"),
                        result.getString("input_sha256"),
                        result.getString("validator_version"),
                        result.getString("result_json"),
                        result.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    @Transactional
    public void save(
            ValidationTaskData task,
            List<ClaimCitationValidationModels.ResearchClaim> claims) {
        jdbc.sql("""
                insert into claim_citation_validation_task(
                    id,hospital_id,project_id,agent_task_id,protocol_id,status,
                    claim_count,citation_link_count,abstract_only_claim_count,
                    needs_expert_review_claim_count,input_sha256,validator_version,
                    result_json,created_at
                ) values(
                    :id,:hospitalId,:projectId,:agentTaskId,:protocolId,:status,
                    :claimCount,:citationLinkCount,:abstractOnlyClaimCount,
                    :needsExpertReviewClaimCount,:inputSha256,:validatorVersion,
                    cast(:resultJson as jsonb),:createdAt
                )
                """)
                .param("id", task.id())
                .param("hospitalId", task.hospitalId())
                .param("projectId", task.projectId())
                .param("agentTaskId", task.agentTaskId())
                .param("protocolId", task.protocolId())
                .param("status", task.status())
                .param("claimCount", task.claimCount())
                .param("citationLinkCount", task.citationLinkCount())
                .param("abstractOnlyClaimCount", task.abstractOnlyClaimCount())
                .param("needsExpertReviewClaimCount", task.needsExpertReviewClaimCount())
                .param("inputSha256", task.inputSha256())
                .param("validatorVersion", task.validatorVersion())
                .param("resultJson", task.resultJson())
                .param("createdAt", Timestamp.from(task.createdAt()))
                .update();
        for (var claim : claims) {
            jdbc.sql("""
                    insert into research_claim(
                        id,hospital_id,validation_task_id,protocol_id,section_id,
                        section_code,claim_order,claim_type,claim_text,support_status,
                        expert_confirmation_status,linked_citation_count,
                        issues_to_confirm_json,created_at
                    ) values(
                        :id,:hospitalId,:validationTaskId,:protocolId,:sectionId,
                        :sectionCode,:claimOrder,:claimType,:claimText,:supportStatus,
                        :expertConfirmationStatus,:linkedCitationCount,
                        cast(:issues as jsonb),:createdAt
                    )
                    """)
                    .param("id", claim.claimId())
                    .param("hospitalId", task.hospitalId())
                    .param("validationTaskId", task.id())
                    .param("protocolId", task.protocolId())
                    .param("sectionId", claim.sectionId())
                    .param("sectionCode", claim.sectionCode())
                    .param("claimOrder", claim.claimOrder())
                    .param("claimType", claim.claimType())
                    .param("claimText", claim.claimText())
                    .param("supportStatus", claim.supportStatus())
                    .param("expertConfirmationStatus", claim.expertConfirmationStatus())
                    .param("linkedCitationCount", claim.citationLinks().size())
                    .param("issues", write(claim.issuesToConfirm()))
                    .param("createdAt", Timestamp.from(task.createdAt()))
                    .update();
            for (var link : claim.citationLinks()) {
                jdbc.sql("""
                        insert into claim_citation_link(
                            id,hospital_id,research_claim_id,link_order,source_type,
                            pmid,doi,title,support_level,evidence_scope,evidence_excerpt,
                            excerpt_location,excerpt_sha256,citation_validation_status,
                            manual_confirmation_status,created_at
                        ) values(
                            :id,:hospitalId,:claimId,:linkOrder,:sourceType,
                            :pmid,:doi,:title,:supportLevel,:evidenceScope,:evidenceExcerpt,
                            :excerptLocation,:excerptSha256,:citationValidationStatus,
                            :manualConfirmationStatus,:createdAt
                        )
                        """)
                        .param("id", link.linkId())
                        .param("hospitalId", task.hospitalId())
                        .param("claimId", claim.claimId())
                        .param("linkOrder", link.linkOrder())
                        .param("sourceType", link.sourceType())
                        .param("pmid", link.pmid())
                        .param("doi", link.doi())
                        .param("title", link.title())
                        .param("supportLevel", link.supportLevel())
                        .param("evidenceScope", link.evidenceScope())
                        .param("evidenceExcerpt", link.evidenceExcerpt())
                        .param("excerptLocation", link.excerptLocation())
                        .param("excerptSha256", link.excerptSha256())
                        .param("citationValidationStatus", link.citationValidationStatus())
                        .param("manualConfirmationStatus", link.manualConfirmationStatus())
                        .param("createdAt", Timestamp.from(task.createdAt()))
                        .update();
            }
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("主张确认项序列化失败", exception);
        }
    }
}
