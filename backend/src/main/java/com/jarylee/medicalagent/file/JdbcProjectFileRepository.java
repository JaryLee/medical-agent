package com.jarylee.medicalagent.file;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.sql.Timestamp;

@Repository
@Profile("postgres")
public class JdbcProjectFileRepository implements ProjectFileRepository {
    private final JdbcClient jdbc;

    public JdbcProjectFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(FileData file) {
        jdbc.sql("""
                insert into project_file(
                    id,hospital_id,project_id,original_name,object_key,content_type,
                    size_bytes,sha256,security_status,matched_rules,scan_engine,
                    extracted_characters,extraction_status,created_at
                ) values(
                    :id,:hospitalId,:projectId,:originalName,:objectKey,:contentType,
                    :sizeBytes,:sha256,:securityStatus,:matchedRules,:scanEngine,
                    :extractedCharacters,:extractionStatus,:createdAt
                )
                """).params(Map.ofEntries(
                        Map.entry("id", file.id()),
                        Map.entry("hospitalId", file.hospitalId()),
                        Map.entry("projectId", file.projectId()),
                        Map.entry("originalName", file.originalName()),
                        Map.entry("objectKey", file.objectKey()),
                        Map.entry("contentType", file.contentType()),
                        Map.entry("sizeBytes", file.sizeBytes()),
                        Map.entry("sha256", file.sha256()),
                        Map.entry("securityStatus", file.securityStatus()),
                        Map.entry("matchedRules", file.matchedRules()),
                        Map.entry("scanEngine", file.scanEngine()),
                        Map.entry("extractedCharacters", file.extractedCharacters()),
                        Map.entry("extractionStatus", file.extractionStatus()),
                        Map.entry("createdAt", Timestamp.from(file.createdAt()))
                )).update();
    }
}
