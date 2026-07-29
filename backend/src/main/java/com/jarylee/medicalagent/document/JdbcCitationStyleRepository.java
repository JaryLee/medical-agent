package com.jarylee.medicalagent.document;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcCitationStyleRepository implements CitationStyleRepository {
    private final JdbcClient jdbc;

    public JdbcCitationStyleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int nextVersion(UUID hospitalId, String styleCode) {
        Integer value = jdbc.sql("""
                select coalesce(max(version_no),0)+1
                from citation_style_version
                where hospital_id=:hospitalId and style_code=:styleCode
                """)
                .param("hospitalId", hospitalId)
                .param("styleCode", styleCode)
                .query(Integer.class)
                .single();
        return value == null ? 1 : value;
    }

    @Override
    public StyleData create(StyleData value) {
        jdbc.sql("""
                insert into citation_style_version(
                    id,hospital_id,style_code,style_name,version_no,status,
                    layout,author_limit,et_al_text,include_pmid,include_doi,
                    include_evidence_scope,evidence_scope_label,
                    created_by,created_at,published_by,published_at,version
                ) values(
                    :id,:hospitalId,:styleCode,:styleName,:versionNo,:status,
                    :layout,:authorLimit,:etAlText,:includePmid,:includeDoi,
                    :includeEvidenceScope,:evidenceScopeLabel,
                    :createdBy,:createdAt,:publishedBy,:publishedAt,:version
                )
                """)
                .param("id", value.id())
                .param("hospitalId", value.hospitalId())
                .param("styleCode", value.styleCode())
                .param("styleName", value.styleName())
                .param("versionNo", value.versionNo())
                .param("status", value.status())
                .param("layout", value.layout())
                .param("authorLimit", value.authorLimit())
                .param("etAlText", value.etAlText())
                .param("includePmid", value.includePmid())
                .param("includeDoi", value.includeDoi())
                .param("includeEvidenceScope", value.includeEvidenceScope())
                .param("evidenceScopeLabel", value.evidenceScopeLabel())
                .param("createdBy", value.createdBy())
                .param("createdAt", Timestamp.from(value.createdAt()))
                .param("publishedBy", value.publishedBy())
                .param("publishedAt", value.publishedAt() == null
                        ? null : Timestamp.from(value.publishedAt()))
                .param("version", value.version())
                .update();
        return value;
    }

    @Override
    public List<StyleData> findAll(UUID hospitalId) {
        return jdbc.sql("""
                select * from citation_style_version
                where hospital_id=:hospitalId
                order by created_at desc,id
                """)
                .param("hospitalId", hospitalId)
                .query(this::map)
                .list();
    }

    @Override
    public Optional<StyleData> findById(UUID hospitalId, UUID styleId) {
        return jdbc.sql("""
                select * from citation_style_version
                where hospital_id=:hospitalId and id=:styleId
                """)
                .param("hospitalId", hospitalId)
                .param("styleId", styleId)
                .query(this::map)
                .optional();
    }

    @Override
    @Transactional
    public Optional<StyleData> publish(
            UUID hospitalId, UUID styleId, UUID publishedBy,
            Instant publishedAt, long expectedVersion) {
        StyleData current = findById(hospitalId, styleId).orElse(null);
        if (current == null || current.version() != expectedVersion
                || !"VALIDATED".equals(current.status())) {
            return Optional.empty();
        }
        jdbc.sql("""
                update citation_style_version
                set status='ARCHIVED',version=version+1
                where hospital_id=:hospitalId and style_code=:styleCode
                  and status='PUBLISHED'
                """)
                .param("hospitalId", hospitalId)
                .param("styleCode", current.styleCode())
                .update();
        int updated = jdbc.sql("""
                update citation_style_version
                set status='PUBLISHED',published_by=:publishedBy,
                    published_at=:publishedAt,version=version+1
                where hospital_id=:hospitalId and id=:styleId
                  and status='VALIDATED' and version=:expectedVersion
                """)
                .param("publishedBy", publishedBy)
                .param("publishedAt", Timestamp.from(publishedAt))
                .param("hospitalId", hospitalId)
                .param("styleId", styleId)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1 ? findById(hospitalId, styleId) : Optional.empty();
    }

    private StyleData map(ResultSet result, int row) throws SQLException {
        return new StyleData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getString("style_code"),
                result.getString("style_name"),
                result.getInt("version_no"),
                result.getString("status"),
                result.getString("layout"),
                result.getInt("author_limit"),
                result.getString("et_al_text"),
                result.getBoolean("include_pmid"),
                result.getBoolean("include_doi"),
                result.getBoolean("include_evidence_scope"),
                result.getString("evidence_scope_label"),
                result.getObject("created_by", UUID.class),
                result.getTimestamp("created_at").toInstant(),
                result.getObject("published_by", UUID.class),
                result.getTimestamp("published_at") == null ? null
                        : result.getTimestamp("published_at").toInstant(),
                result.getLong("version"));
    }
}
