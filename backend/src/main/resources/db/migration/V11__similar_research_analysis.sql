CREATE TABLE similar_research_analysis_task (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    agent_task_id UUID NOT NULL REFERENCES ai_agent_task(id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    analyzed_source_count INTEGER,
    excluded_citation_count INTEGER,
    high_similarity_count INTEGER,
    moderate_similarity_count INTEGER,
    low_similarity_count INTEGER,
    gap_count INTEGER,
    input_sha256 CHAR(64) NOT NULL,
    algorithm_version VARCHAR(80) NOT NULL,
    database_scope_json JSONB NOT NULL,
    conclusion TEXT,
    result_json JSONB,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_similar_research_analysis_status
        CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    CONSTRAINT ck_similar_research_analysis_counts CHECK (
        (analyzed_source_count IS NULL OR analyzed_source_count >= 0) AND
        (excluded_citation_count IS NULL OR excluded_citation_count >= 0) AND
        (high_similarity_count IS NULL OR high_similarity_count >= 0) AND
        (moderate_similarity_count IS NULL OR moderate_similarity_count >= 0) AND
        (low_similarity_count IS NULL OR low_similarity_count >= 0) AND
        (gap_count IS NULL OR gap_count >= 0)
    )
);

CREATE INDEX idx_similar_research_analysis_agent
    ON similar_research_analysis_task(hospital_id, agent_task_id, started_at DESC);

CREATE TABLE similar_research_comparison (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    analysis_task_id UUID NOT NULL
        REFERENCES similar_research_analysis_task(id) ON DELETE CASCADE,
    literature_id UUID REFERENCES literature_record(id),
    clinical_trial_id UUID REFERENCES clinical_trial_record(id),
    source_type VARCHAR(40) NOT NULL,
    source_identifier VARCHAR(40) NOT NULL,
    pmid VARCHAR(20),
    doi TEXT,
    nct_id VARCHAR(11),
    title TEXT NOT NULL,
    publication_or_completion_date VARCHAR(40),
    similarity_score INTEGER NOT NULL,
    similarity_tier VARCHAR(20) NOT NULL,
    verification_status VARCHAR(60) NOT NULL,
    evidence_scope VARCHAR(60) NOT NULL,
    dimensions_json JSONB NOT NULL,
    differences_json JSONB NOT NULL,
    linked_source_identifiers_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_similar_research_comparison
        UNIQUE (analysis_task_id, source_type, source_identifier),
    CONSTRAINT ck_similar_research_source_type
        CHECK (source_type IN ('PUBMED_ARTICLE','TRIAL_REGISTRY')),
    CONSTRAINT ck_similar_research_score
        CHECK (similarity_score BETWEEN 0 AND 100),
    CONSTRAINT ck_similar_research_tier
        CHECK (similarity_tier IN ('HIGH','MODERATE','LOW')),
    CONSTRAINT ck_similar_research_source_fk CHECK (
        (source_type='PUBMED_ARTICLE' AND literature_id IS NOT NULL
            AND clinical_trial_id IS NULL AND pmid IS NOT NULL) OR
        (source_type='TRIAL_REGISTRY' AND clinical_trial_id IS NOT NULL
            AND literature_id IS NULL AND nct_id IS NOT NULL)
    )
);

CREATE INDEX idx_similar_research_comparison_score
    ON similar_research_comparison(hospital_id, similarity_tier, similarity_score DESC);

CREATE TABLE research_gap_suggestion (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    analysis_task_id UUID NOT NULL
        REFERENCES similar_research_analysis_task(id) ON DELETE CASCADE,
    gap_code VARCHAR(80) NOT NULL,
    statement TEXT NOT NULL,
    basis TEXT NOT NULL,
    basis_source_identifiers_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_research_gap_suggestion UNIQUE (analysis_task_id, gap_code)
);

CREATE INDEX idx_research_gap_suggestion_project
    ON research_gap_suggestion(hospital_id, project_id, created_at DESC);
