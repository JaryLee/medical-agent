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
public class JdbcSimilarResearchAnalysisRepository
        implements SimilarResearchAnalysisRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcSimilarResearchAnalysisRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void create(AnalysisData analysis) {
        jdbc.sql("""
                insert into similar_research_analysis_task(
                    id,hospital_id,project_id,agent_task_id,status,started_at,
                    input_sha256,algorithm_version,database_scope_json
                ) values(
                    :id,:hospitalId,:projectId,:agentTaskId,'RUNNING',:startedAt,
                    :inputSha256,:algorithmVersion,cast(:databaseScope as jsonb)
                )
                """)
                .param("id", analysis.id())
                .param("hospitalId", analysis.hospitalId())
                .param("projectId", analysis.projectId())
                .param("agentTaskId", analysis.agentTaskId())
                .param("startedAt", Timestamp.from(analysis.startedAt()))
                .param("inputSha256", analysis.inputSha256())
                .param("algorithmVersion", analysis.algorithmVersion())
                .param("databaseScope", analysis.databaseScopeJson())
                .update();
    }

    @Override
    @Transactional
    public void complete(
            AnalysisData analysis,
            List<SimilarResearchAnalysisModels.SimilarResearch> comparisons,
            List<SimilarResearchAnalysisModels.ResearchGap> gaps) {
        int updated = jdbc.sql("""
                update similar_research_analysis_task set status='COMPLETED',
                    completed_at=:completedAt,analyzed_source_count=:analyzedSourceCount,
                    excluded_citation_count=:excludedCitationCount,
                    high_similarity_count=:highCount,
                    moderate_similarity_count=:moderateCount,
                    low_similarity_count=:lowCount,gap_count=:gapCount,
                    conclusion=:conclusion,result_json=cast(:resultJson as jsonb),
                    error_code=null,error_message=null,version=version+1
                where hospital_id=:hospitalId and id=:id and status='RUNNING'
                """)
                .param("completedAt", Timestamp.from(analysis.completedAt()))
                .param("analyzedSourceCount", analysis.analyzedSourceCount())
                .param("excludedCitationCount", analysis.excludedCitationCount())
                .param("highCount", analysis.highSimilarityCount())
                .param("moderateCount", analysis.moderateSimilarityCount())
                .param("lowCount", analysis.lowSimilarityCount())
                .param("gapCount", analysis.gapCount())
                .param("conclusion", analysis.conclusion())
                .param("resultJson", analysis.resultJson())
                .param("hospitalId", analysis.hospitalId())
                .param("id", analysis.id())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("相似研究分析任务当前不可完成");
        }

        for (var comparison : comparisons) {
            UUID literatureId = "PUBMED_ARTICLE".equals(comparison.sourceType())
                    ? findLiteratureId(analysis.hospitalId(), comparison.pmid()) : null;
            UUID trialId = "TRIAL_REGISTRY".equals(comparison.sourceType())
                    ? findTrialId(analysis.hospitalId(), comparison.nctId()) : null;
            jdbc.sql("""
                    insert into similar_research_comparison(
                        id,hospital_id,analysis_task_id,literature_id,clinical_trial_id,
                        source_type,source_identifier,pmid,doi,nct_id,title,
                        publication_or_completion_date,similarity_score,similarity_tier,
                        verification_status,evidence_scope,dimensions_json,differences_json,
                        linked_source_identifiers_json
                    ) values(
                        :id,:hospitalId,:analysisTaskId,:literatureId,:trialId,
                        :sourceType,:sourceIdentifier,:pmid,:doi,:nctId,:title,
                        :sourceDate,:score,:tier,:verificationStatus,:evidenceScope,
                        cast(:dimensions as jsonb),cast(:differences as jsonb),
                        cast(:linkedSources as jsonb)
                    )
                    """)
                    .param("id", UUID.randomUUID())
                    .param("hospitalId", analysis.hospitalId())
                    .param("analysisTaskId", analysis.id())
                    .param("literatureId", literatureId)
                    .param("trialId", trialId)
                    .param("sourceType", comparison.sourceType())
                    .param("sourceIdentifier", comparison.sourceIdentifier())
                    .param("pmid", comparison.pmid())
                    .param("doi", comparison.doi())
                    .param("nctId", comparison.nctId())
                    .param("title", comparison.title())
                    .param("sourceDate", comparison.publicationOrCompletionDate())
                    .param("score", comparison.similarityScore())
                    .param("tier", comparison.similarityTier())
                    .param("verificationStatus", comparison.verificationStatus())
                    .param("evidenceScope", comparison.evidenceScope())
                    .param("dimensions", write(comparison.dimensions()))
                    .param("differences", write(comparison.differences()))
                    .param("linkedSources", write(comparison.linkedSourceIdentifiers()))
                    .update();
        }

        for (var gap : gaps) {
            jdbc.sql("""
                    insert into research_gap_suggestion(
                        id,hospital_id,project_id,analysis_task_id,gap_code,
                        statement,basis,basis_source_identifiers_json
                    ) values(
                        :id,:hospitalId,:projectId,:analysisTaskId,:gapCode,
                        :statement,:basis,cast(:basisSources as jsonb)
                    )
                    """)
                    .param("id", UUID.randomUUID())
                    .param("hospitalId", analysis.hospitalId())
                    .param("projectId", analysis.projectId())
                    .param("analysisTaskId", analysis.id())
                    .param("gapCode", gap.code())
                    .param("statement", gap.statement())
                    .param("basis", gap.basis())
                    .param("basisSources", write(gap.basisSourceIdentifiers()))
                    .update();
        }
    }

    @Override
    public void fail(
            UUID hospitalId, UUID analysisId, String errorCode,
            String errorMessage, Instant completedAt) {
        jdbc.sql("""
                update similar_research_analysis_task set status='FAILED',
                    completed_at=:completedAt,error_code=:errorCode,
                    error_message=:errorMessage,version=version+1
                where hospital_id=:hospitalId and id=:id and status='RUNNING'
                """)
                .param("completedAt", Timestamp.from(completedAt))
                .param("errorCode", errorCode)
                .param("errorMessage", truncate(errorMessage))
                .param("hospitalId", hospitalId)
                .param("id", analysisId)
                .update();
    }

    private UUID findLiteratureId(UUID hospitalId, String pmid) {
        return jdbc.sql("""
                select id from literature_record
                where hospital_id=:hospitalId and pmid=:pmid
                """)
                .param("hospitalId", hospitalId)
                .param("pmid", pmid)
                .query(UUID.class).single();
    }

    private UUID findTrialId(UUID hospitalId, String nctId) {
        return jdbc.sql("""
                select id from clinical_trial_record
                where hospital_id=:hospitalId and nct_id=:nctId
                """)
                .param("hospitalId", hospitalId)
                .param("nctId", nctId)
                .query(UUID.class).single();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("相似研究记录序列化失败", exception);
        }
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
