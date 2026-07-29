CREATE TABLE literature_search_task (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id),
    agent_task_id UUID NOT NULL REFERENCES ai_agent_task(id) ON DELETE CASCADE,
    database_name VARCHAR(40) NOT NULL,
    original_question TEXT NOT NULL,
    structured_concepts_json JSONB NOT NULL,
    query_text TEXT NOT NULL,
    query_version VARCHAR(80) NOT NULL,
    filters_json JSONB NOT NULL,
    status VARCHAR(40) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    total_result_count BIGINT,
    returned_result_count INTEGER,
    raw_object_key VARCHAR(1000),
    raw_response_sha256 CHAR(64),
    raw_content_type VARCHAR(120),
    tool_version VARCHAR(80),
    external_request_count INTEGER,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_literature_search_counts CHECK (
        (total_result_count IS NULL OR total_result_count >= 0)
        AND (returned_result_count IS NULL OR returned_result_count >= 0)
        AND (external_request_count IS NULL OR external_request_count >= 0)
    )
);

CREATE INDEX idx_literature_search_project_time
    ON literature_search_task(hospital_id, project_id, started_at DESC);
CREATE INDEX idx_literature_search_agent_task
    ON literature_search_task(hospital_id, agent_task_id);

CREATE TABLE literature_record (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    pmid VARCHAR(20) NOT NULL,
    doi VARCHAR(300),
    title TEXT NOT NULL,
    authors_json JSONB NOT NULL,
    journal TEXT,
    publication_date VARCHAR(80),
    abstract_text TEXT,
    evidence_scope VARCHAR(40) NOT NULL,
    verified BOOLEAN NOT NULL,
    source VARCHAR(80) NOT NULL,
    raw_metadata_json JSONB NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_literature_record_hospital_pmid UNIQUE (hospital_id, pmid),
    CONSTRAINT ck_literature_record_pmid CHECK (pmid ~ '^[0-9]+$')
);

CREATE INDEX idx_literature_record_doi
    ON literature_record(hospital_id, doi)
    WHERE doi IS NOT NULL;

CREATE TABLE project_literature (
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    literature_id UUID NOT NULL REFERENCES literature_record(id),
    search_task_id UUID NOT NULL REFERENCES literature_search_task(id) ON DELETE CASCADE,
    review_status VARCHAR(40) NOT NULL DEFAULT 'DISCOVERED',
    relevance_status VARCHAR(40) NOT NULL DEFAULT 'UNASSESSED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (search_task_id, literature_id)
);

CREATE INDEX idx_project_literature_project
    ON project_literature(hospital_id, project_id, created_at DESC);
