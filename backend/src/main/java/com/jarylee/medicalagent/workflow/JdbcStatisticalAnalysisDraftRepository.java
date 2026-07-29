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
public class JdbcStatisticalAnalysisDraftRepository
        implements StatisticalAnalysisDraftRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcStatisticalAnalysisDraftRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<DraftData> findByAgentTask(UUID hospitalId, UUID agentTaskId) {
        return jdbc.sql("""
                select id,hospital_id,project_id,agent_task_id,protocol_id,status,
                    study_type,primary_outcome,outcome_type_status,parameter_count,
                    input_sha256,generator_version,result_json::text,created_at
                from statistical_analysis_draft
                where hospital_id=:hospitalId and agent_task_id=:agentTaskId
                """)
                .param("hospitalId", hospitalId)
                .param("agentTaskId", agentTaskId)
                .query((result, rowNum) -> new DraftData(
                        result.getObject("id", UUID.class),
                        result.getObject("hospital_id", UUID.class),
                        result.getObject("project_id", UUID.class),
                        result.getObject("agent_task_id", UUID.class),
                        result.getObject("protocol_id", UUID.class),
                        result.getString("status"),
                        result.getString("study_type"),
                        result.getString("primary_outcome"),
                        result.getString("outcome_type_status"),
                        result.getInt("parameter_count"),
                        result.getString("input_sha256"),
                        result.getString("generator_version"),
                        result.getString("result_json"),
                        result.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    @Transactional
    public void save(
            DraftData draft,
            List<StatisticalAnalysisModels.SampleSizeParameter> parameters,
            ResearchProtocolModels.ProtocolSection statisticalSection) {
        jdbc.sql("""
                insert into statistical_analysis_draft(
                    id,hospital_id,project_id,agent_task_id,protocol_id,status,
                    study_type,primary_outcome,outcome_type_status,parameter_count,
                    input_sha256,generator_version,result_json,created_at
                ) values(
                    :id,:hospitalId,:projectId,:agentTaskId,:protocolId,'DRAFT',
                    :studyType,:primaryOutcome,:outcomeTypeStatus,:parameterCount,
                    :inputSha256,:generatorVersion,cast(:resultJson as jsonb),:createdAt
                )
                """)
                .param("id", draft.id())
                .param("hospitalId", draft.hospitalId())
                .param("projectId", draft.projectId())
                .param("agentTaskId", draft.agentTaskId())
                .param("protocolId", draft.protocolId())
                .param("studyType", draft.studyType())
                .param("primaryOutcome", draft.primaryOutcome())
                .param("outcomeTypeStatus", draft.outcomeTypeStatus())
                .param("parameterCount", draft.parameterCount())
                .param("inputSha256", draft.inputSha256())
                .param("generatorVersion", draft.generatorVersion())
                .param("resultJson", draft.resultJson())
                .param("createdAt", Timestamp.from(draft.createdAt()))
                .update();
        for (int index = 0; index < parameters.size(); index++) {
            var parameter = parameters.get(index);
            jdbc.sql("""
                    insert into sample_size_parameter_requirement(
                        id,hospital_id,statistical_draft_id,sort_order,parameter_code,
                        label,is_required,value_status,value_text,unit,rationale,created_at
                    ) values(
                        gen_random_uuid(),:hospitalId,:draftId,:sortOrder,:code,:label,
                        :required,:valueStatus,:value,:unit,:rationale,:createdAt
                    )
                    """)
                    .param("hospitalId", draft.hospitalId())
                    .param("draftId", draft.id())
                    .param("sortOrder", index + 1)
                    .param("code", parameter.code())
                    .param("label", parameter.label())
                    .param("required", parameter.required())
                    .param("valueStatus", parameter.valueStatus())
                    .param("value", parameter.value())
                    .param("unit", parameter.unit())
                    .param("rationale", parameter.rationale())
                    .param("createdAt", Timestamp.from(draft.createdAt()))
                    .update();
        }
        UUID sectionId = jdbc.sql("""
                select id from research_protocol_section
                where hospital_id=:hospitalId and protocol_id=:protocolId
                  and section_code='STATISTICAL_ANALYSIS'
                  and current_version_no=1 and status='DRAFT'
                """)
                .param("hospitalId", draft.hospitalId())
                .param("protocolId", draft.protocolId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "统计分析章节已被修改，不能静默覆盖"));
        if (!sectionId.equals(statisticalSection.sectionId())
                || statisticalSection.versionNo() != 2) {
            throw new IllegalStateException("统计分析章节版本标识不一致");
        }
        jdbc.sql("""
                insert into research_protocol_section_version(
                    id,hospital_id,section_id,version_no,content,content_format,
                    origin,evidence_status,source_identifiers_json,
                    issues_to_confirm_json,change_reason,created_at
                ) values(
                    gen_random_uuid(),:hospitalId,:sectionId,2,:content,:contentFormat,
                    :origin,:evidenceStatus,cast(:sources as jsonb),cast(:issues as jsonb),
                    'STEP14_STATISTICAL_DRAFT',:createdAt
                )
                """)
                .param("hospitalId", draft.hospitalId())
                .param("sectionId", sectionId)
                .param("content", statisticalSection.content())
                .param("contentFormat", statisticalSection.contentFormat())
                .param("origin", statisticalSection.origin())
                .param("evidenceStatus", statisticalSection.evidenceStatus())
                .param("sources", write(statisticalSection.sourceIdentifiers()))
                .param("issues", write(statisticalSection.issuesToConfirm()))
                .param("createdAt", Timestamp.from(draft.createdAt()))
                .update();
        int updated = jdbc.sql("""
                update research_protocol_section
                set current_version_no=2,updated_at=:createdAt,version=version+1
                where hospital_id=:hospitalId and id=:sectionId
                  and current_version_no=1 and status='DRAFT'
                """)
                .param("createdAt", Timestamp.from(draft.createdAt()))
                .param("hospitalId", draft.hospitalId())
                .param("sectionId", sectionId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("统计分析章节版本并发更新失败");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("统计分析章节元数据序列化失败", exception);
        }
    }
}
