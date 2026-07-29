package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.literature.LiteratureSearchRepository.SearchData;
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
public class JdbcClinicalTrialSearchRepository implements ClinicalTrialSearchRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcClinicalTrialSearchRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void create(SearchData search) {
        jdbc.sql("""
                insert into literature_search_task(
                    id,hospital_id,project_id,agent_task_id,database_name,
                    original_question,structured_concepts_json,query_text,query_version,
                    filters_json,status,started_at
                ) values(
                    :id,:hospitalId,:projectId,:agentTaskId,:database,
                    :originalQuestion,cast(:concepts as jsonb),:query,:queryVersion,
                    cast(:filters as jsonb),'RUNNING',:startedAt
                )
                """).param("id", search.id()).param("hospitalId", search.hospitalId())
                .param("projectId", search.projectId()).param("agentTaskId", search.agentTaskId())
                .param("database", search.database()).param("originalQuestion", search.originalQuestion())
                .param("concepts", search.structuredConceptsJson()).param("query", search.query())
                .param("queryVersion", search.queryVersion()).param("filters", search.filtersJson())
                .param("startedAt", Timestamp.from(search.startedAt())).update();
    }

    @Override
    @Transactional
    public void complete(SearchData search, List<ClinicalTrialsSearchModels.Trial> trials) {
        int updated = jdbc.sql("""
                update literature_search_task set status='COMPLETED',
                    completed_at=:completedAt,total_result_count=:totalCount,
                    returned_result_count=:returnedCount,raw_object_key=:rawObjectKey,
                    raw_response_sha256=:rawHash,raw_content_type=:rawContentType,
                    tool_version=:toolVersion,external_request_count=:requestCount,
                    error_code=null,error_message=null,version=version+1
                where hospital_id=:hospitalId and id=:id and status='RUNNING'
                """).param("completedAt", Timestamp.from(search.completedAt()))
                .param("totalCount", search.totalResultCount())
                .param("returnedCount", search.returnedResultCount())
                .param("rawObjectKey", search.rawObjectKey())
                .param("rawHash", search.rawResponseSha256())
                .param("rawContentType", search.rawContentType())
                .param("toolVersion", search.toolVersion())
                .param("requestCount", search.externalRequestCount())
                .param("hospitalId", search.hospitalId()).param("id", search.id()).update();
        if (updated != 1) throw new IllegalStateException("临床试验检索记录当前不可完成");
        for (ClinicalTrialsSearchModels.Trial trial : trials) {
            UUID trialId = upsertTrial(search.hospitalId(), trial, search.completedAt());
            jdbc.sql("""
                    insert into project_clinical_trial(
                        hospital_id,project_id,clinical_trial_id,search_task_id
                    ) values(:hospitalId,:projectId,:trialId,:searchTaskId)
                    on conflict do nothing
                    """).param("hospitalId", search.hospitalId())
                    .param("projectId", search.projectId()).param("trialId", trialId)
                    .param("searchTaskId", search.id()).update();
        }
    }

    @Override
    public void fail(UUID hospitalId, UUID searchId, String errorCode,
                     String errorMessage, Instant completedAt) {
        jdbc.sql("""
                update literature_search_task set status='FAILED',completed_at=:completedAt,
                    error_code=:errorCode,error_message=:errorMessage,version=version+1
                where hospital_id=:hospitalId and id=:id and status='RUNNING'
                """).param("completedAt", Timestamp.from(completedAt))
                .param("errorCode", errorCode).param("errorMessage", truncate(errorMessage))
                .param("hospitalId", hospitalId).param("id", searchId).update();
    }

    private UUID upsertTrial(
            UUID hospitalId, ClinicalTrialsSearchModels.Trial trial, Instant fetchedAt) {
        UUID candidate = UUID.randomUUID();
        return jdbc.sql("""
                insert into clinical_trial_record(
                    id,hospital_id,nct_id,brief_title,official_title,overall_status,
                    study_type,phases_json,conditions_json,interventions_json,brief_summary,
                    primary_outcomes_json,lead_sponsor,start_date,completion_date,enrollment,
                    countries_json,has_results,evidence_scope,verified,source,
                    linked_pmids_json,raw_metadata_json,fetched_at
                ) values(
                    :id,:hospitalId,:nctId,:briefTitle,:officialTitle,:overallStatus,
                    :studyType,cast(:phases as jsonb),cast(:conditions as jsonb),
                    cast(:interventions as jsonb),:briefSummary,cast(:primaryOutcomes as jsonb),
                    :leadSponsor,:startDate,:completionDate,:enrollment,cast(:countries as jsonb),
                    :hasResults,:evidenceScope,:verified,:source,cast(:linkedPmids as jsonb),
                    cast(:rawMetadata as jsonb),:fetchedAt
                )
                on conflict(hospital_id,nct_id) do update set
                    brief_title=excluded.brief_title,official_title=excluded.official_title,
                    overall_status=excluded.overall_status,study_type=excluded.study_type,
                    phases_json=excluded.phases_json,conditions_json=excluded.conditions_json,
                    interventions_json=excluded.interventions_json,
                    brief_summary=excluded.brief_summary,
                    primary_outcomes_json=excluded.primary_outcomes_json,
                    lead_sponsor=excluded.lead_sponsor,start_date=excluded.start_date,
                    completion_date=excluded.completion_date,enrollment=excluded.enrollment,
                    countries_json=excluded.countries_json,has_results=excluded.has_results,
                    evidence_scope=excluded.evidence_scope,verified=excluded.verified,
                    source=excluded.source,linked_pmids_json=excluded.linked_pmids_json,
                    raw_metadata_json=excluded.raw_metadata_json,fetched_at=excluded.fetched_at,
                    version=clinical_trial_record.version+1
                returning id
                """).param("id", candidate).param("hospitalId", hospitalId)
                .param("nctId", trial.nctId()).param("briefTitle", trial.briefTitle())
                .param("officialTitle", trial.officialTitle())
                .param("overallStatus", trial.overallStatus()).param("studyType", trial.studyType())
                .param("phases", write(trial.phases())).param("conditions", write(trial.conditions()))
                .param("interventions", write(trial.interventions()))
                .param("briefSummary", trial.briefSummary())
                .param("primaryOutcomes", write(trial.primaryOutcomes()))
                .param("leadSponsor", trial.leadSponsor()).param("startDate", trial.startDate())
                .param("completionDate", trial.completionDate())
                .param("enrollment", trial.enrollment()).param("countries", write(trial.countries()))
                .param("hasResults", trial.hasResults())
                .param("evidenceScope", trial.evidenceScope())
                .param("verified", trial.verified()).param("source", trial.source())
                .param("linkedPmids", write(trial.linkedPmids()))
                .param("rawMetadata", write(trial))
                .param("fetchedAt", Timestamp.from(fetchedAt))
                .query(UUID.class).single();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("临床试验记录序列化失败", exception);
        }
    }

    private String truncate(String value) {
        if (value == null) return "未知错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
