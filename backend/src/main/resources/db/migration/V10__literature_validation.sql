CREATE TABLE literature_validation_task (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    agent_task_id UUID NOT NULL REFERENCES ai_agent_task(id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    validation_count INTEGER,
    evidence_link_count INTEGER,
    raw_object_key TEXT,
    raw_response_sha256 CHAR(64),
    raw_content_type VARCHAR(120),
    tool_version VARCHAR(80),
    external_request_count INTEGER,
    cache_hit_count INTEGER,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_literature_validation_status
        CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    CONSTRAINT ck_literature_validation_counts
        CHECK (
            (validation_count IS NULL OR validation_count >= 0) AND
            (evidence_link_count IS NULL OR evidence_link_count >= 0) AND
            (external_request_count IS NULL OR external_request_count >= 0) AND
            (cache_hit_count IS NULL OR cache_hit_count >= 0)
        )
);

CREATE INDEX idx_literature_validation_task_agent
    ON literature_validation_task(hospital_id, agent_task_id, started_at DESC);

CREATE TABLE citation_validation_record (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    validation_task_id UUID NOT NULL
        REFERENCES literature_validation_task(id) ON DELETE CASCADE,
    literature_id UUID REFERENCES literature_record(id),
    pmid VARCHAR(20) NOT NULL,
    doi TEXT,
    validation_source VARCHAR(40) NOT NULL,
    status VARCHAR(60) NOT NULL,
    field_results_json JSONB NOT NULL,
    crossref_metadata_json JSONB,
    message TEXT,
    validated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_citation_validation
        UNIQUE (validation_task_id, pmid, validation_source),
    CONSTRAINT ck_citation_validation_status CHECK (
        status IN (
            'VERIFIED','VERIFIED_WITH_METADATA_DIFFERENCES','MISMATCH',
            'CROSSREF_NOT_FOUND','DOI_NOT_AVAILABLE'
        )
    )
);

CREATE INDEX idx_citation_validation_literature
    ON citation_validation_record(hospital_id, literature_id, validated_at DESC);

CREATE TABLE evidence_source_link (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    validation_task_id UUID NOT NULL
        REFERENCES literature_validation_task(id) ON DELETE CASCADE,
    clinical_trial_id UUID NOT NULL REFERENCES clinical_trial_record(id),
    literature_id UUID REFERENCES literature_record(id),
    nct_id VARCHAR(11) NOT NULL,
    pmid VARCHAR(20) NOT NULL,
    relationship_type VARCHAR(60) NOT NULL,
    verification_status VARCHAR(40) NOT NULL,
    source_reference_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_evidence_source_link
        UNIQUE (validation_task_id, nct_id, pmid, relationship_type),
    CONSTRAINT ck_evidence_source_link_nct CHECK (nct_id ~ '^NCT[0-9]{8}$'),
    CONSTRAINT ck_evidence_source_link_status
        CHECK (verification_status IN ('RESOLVED','UNRESOLVED_PUBMED'))
);

CREATE INDEX idx_evidence_source_link_project
    ON evidence_source_link(hospital_id, project_id, created_at DESC);
