package com.jarylee.medicalagent.literature;

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
public class JdbcLiteratureValidationRepository implements LiteratureValidationRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcLiteratureValidationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void create(ValidationData validation) {
        jdbc.sql("""
                insert into literature_validation_task(
                    id,hospital_id,project_id,agent_task_id,status,started_at
                ) values(:id,:hospitalId,:projectId,:agentTaskId,'RUNNING',:startedAt)
                """)
                .param("id", validation.id())
                .param("hospitalId", validation.hospitalId())
                .param("projectId", validation.projectId())
                .param("agentTaskId", validation.agentTaskId())
                .param("startedAt", Timestamp.from(validation.startedAt()))
                .update();
    }

    @Override
    @Transactional
    public void complete(
            ValidationData validation,
            List<LiteratureValidationModels.CitationValidation> citations,
            List<LiteratureValidationModels.EvidenceLink> evidenceLinks) {
        int updated = jdbc.sql("""
                update literature_validation_task set status='COMPLETED',
                    completed_at=:completedAt,validation_count=:validationCount,
                    evidence_link_count=:evidenceLinkCount,raw_object_key=:rawObjectKey,
                    raw_response_sha256=:rawHash,raw_content_type=:rawContentType,
                    tool_version=:toolVersion,external_request_count=:requestCount,
                    cache_hit_count=:cacheHitCount,error_code=null,error_message=null,
                    version=version+1
                where hospital_id=:hospitalId and id=:id and status='RUNNING'
                """)
                .param("completedAt", Timestamp.from(validation.completedAt()))
                .param("validationCount", validation.validationCount())
                .param("evidenceLinkCount", validation.evidenceLinkCount())
                .param("rawObjectKey", validation.rawObjectKey())
                .param("rawHash", validation.rawResponseSha256())
                .param("rawContentType", validation.rawContentType())
                .param("toolVersion", validation.toolVersion())
                .param("requestCount", validation.externalRequestCount())
                .param("cacheHitCount", validation.cacheHitCount())
                .param("hospitalId", validation.hospitalId())
                .param("id", validation.id())
                .update();
        if (updated != 1) throw new IllegalStateException("文献验证任务当前不可完成");

        for (var citation : citations) {
            UUID literatureId = findLiteratureId(validation.hospitalId(), citation.pmid());
            jdbc.sql("""
                    insert into citation_validation_record(
                        id,hospital_id,validation_task_id,literature_id,pmid,doi,
                        validation_source,status,field_results_json,
                        crossref_metadata_json,message,validated_at
                    ) values(
                        :id,:hospitalId,:validationTaskId,:literatureId,:pmid,:doi,
                        :validationSource,:status,cast(:fieldResults as jsonb),
                        cast(:metadata as jsonb),:message,:validatedAt
                    )
                    """)
                    .param("id", UUID.randomUUID())
                    .param("hospitalId", validation.hospitalId())
                    .param("validationTaskId", validation.id())
                    .param("literatureId", literatureId)
                    .param("pmid", citation.pmid())
                    .param("doi", citation.doi())
                    .param("validationSource", citation.validationSource())
                    .param("status", citation.status())
                    .param("fieldResults", write(citation.fieldChecks()))
                    .param("metadata", write(citation.crossrefMetadata()))
                    .param("message", citation.message())
                    .param("validatedAt", Timestamp.from(validation.completedAt()))
                    .update();
        }

        for (var link : evidenceLinks) {
            UUID trialId = findTrialId(validation.hospitalId(), link.nctId());
            UUID literatureId = findOptionalLiteratureId(
                    validation.hospitalId(), link.pmid());
            jdbc.sql("""
                    insert into evidence_source_link(
                        id,hospital_id,project_id,validation_task_id,clinical_trial_id,
                        literature_id,nct_id,pmid,relationship_type,
                        verification_status,source_reference_json
                    ) values(
                        :id,:hospitalId,:projectId,:validationTaskId,:trialId,
                        :literatureId,:nctId,:pmid,:relationship,:status,
                        cast(:sourceReference as jsonb)
                    )
                    """)
                    .param("id", UUID.randomUUID())
                    .param("hospitalId", validation.hospitalId())
                    .param("projectId", validation.projectId())
                    .param("validationTaskId", validation.id())
                    .param("trialId", trialId)
                    .param("literatureId", literatureId)
                    .param("nctId", link.nctId())
                    .param("pmid", link.pmid())
                    .param("relationship", link.relationship())
                    .param("status", link.status())
                    .param("sourceReference", write(link))
                    .update();
        }
    }

    @Override
    public void fail(
            UUID hospitalId, UUID validationId, String errorCode,
            String errorMessage, Instant completedAt) {
        jdbc.sql("""
                update literature_validation_task set status='FAILED',
                    completed_at=:completedAt,error_code=:errorCode,
                    error_message=:errorMessage,version=version+1
                where hospital_id=:hospitalId and id=:id and status='RUNNING'
                """)
                .param("completedAt", Timestamp.from(completedAt))
                .param("errorCode", errorCode)
                .param("errorMessage", truncate(errorMessage))
                .param("hospitalId", hospitalId)
                .param("id", validationId)
                .update();
    }

    private UUID findLiteratureId(UUID hospitalId, String pmid) {
        return findOptionalLiteratureId(hospitalId, pmid);
    }

    private UUID findOptionalLiteratureId(UUID hospitalId, String pmid) {
        return jdbc.sql("""
                select id from literature_record
                where hospital_id=:hospitalId and pmid=:pmid
                """)
                .param("hospitalId", hospitalId)
                .param("pmid", pmid)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private UUID findTrialId(UUID hospitalId, String nctId) {
        return jdbc.sql("""
                select id from clinical_trial_record
                where hospital_id=:hospitalId and nct_id=:nctId
                """)
                .param("hospitalId", hospitalId)
                .param("nctId", nctId)
                .query(UUID.class)
                .single();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("文献验证记录序列化失败", exception);
        }
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
