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
public class JdbcLiteratureSearchRepository implements LiteratureSearchRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcLiteratureSearchRepository(JdbcClient jdbc, ObjectMapper json) {
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
    public void complete(SearchData search, List<PubMedSearchModels.Article> articles) {
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
        if (updated != 1) throw new IllegalStateException("文献检索记录当前不可完成");
        for (PubMedSearchModels.Article article : articles) {
            UUID literatureId = upsertArticle(search.hospitalId(), article, search.completedAt());
            jdbc.sql("""
                    insert into project_literature(
                        hospital_id,project_id,literature_id,search_task_id
                    ) values(:hospitalId,:projectId,:literatureId,:searchTaskId)
                    on conflict do nothing
                    """).param("hospitalId", search.hospitalId())
                    .param("projectId", search.projectId()).param("literatureId", literatureId)
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

    private UUID upsertArticle(
            UUID hospitalId, PubMedSearchModels.Article article, Instant fetchedAt) {
        UUID candidate = UUID.randomUUID();
        return jdbc.sql("""
                insert into literature_record(
                    id,hospital_id,pmid,doi,title,authors_json,journal,publication_date,
                    abstract_text,evidence_scope,verified,source,raw_metadata_json,fetched_at
                ) values(
                    :id,:hospitalId,:pmid,:doi,:title,cast(:authors as jsonb),:journal,
                    :publicationDate,:abstractText,:evidenceScope,:verified,:source,
                    cast(:rawMetadata as jsonb),:fetchedAt
                )
                on conflict(hospital_id,pmid) do update set
                    doi=excluded.doi,title=excluded.title,authors_json=excluded.authors_json,
                    journal=excluded.journal,publication_date=excluded.publication_date,
                    abstract_text=excluded.abstract_text,evidence_scope=excluded.evidence_scope,
                    verified=excluded.verified,source=excluded.source,
                    raw_metadata_json=excluded.raw_metadata_json,fetched_at=excluded.fetched_at,
                    version=literature_record.version+1
                returning id
                """).param("id", candidate).param("hospitalId", hospitalId)
                .param("pmid", article.pmid()).param("doi", article.doi())
                .param("title", article.title()).param("authors", write(article.authors()))
                .param("journal", article.journal()).param("publicationDate", article.publicationDate())
                .param("abstractText", article.abstractText())
                .param("evidenceScope", article.evidenceScope())
                .param("verified", article.verified()).param("source", article.source())
                .param("rawMetadata", write(article))
                .param("fetchedAt", Timestamp.from(fetchedAt))
                .query(UUID.class).single();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("文献记录序列化失败", exception);
        }
    }

    private String truncate(String value) {
        if (value == null) return "未知错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
