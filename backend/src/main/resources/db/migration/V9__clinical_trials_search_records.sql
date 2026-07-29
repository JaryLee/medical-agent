CREATE TABLE clinical_trial_record (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    nct_id VARCHAR(11) NOT NULL,
    brief_title TEXT NOT NULL,
    official_title TEXT,
    overall_status VARCHAR(80) NOT NULL,
    study_type VARCHAR(80) NOT NULL,
    phases_json JSONB NOT NULL,
    conditions_json JSONB NOT NULL,
    interventions_json JSONB NOT NULL,
    brief_summary TEXT,
    primary_outcomes_json JSONB NOT NULL,
    lead_sponsor TEXT,
    start_date VARCHAR(40),
    completion_date VARCHAR(40),
    enrollment INTEGER,
    countries_json JSONB NOT NULL,
    has_results BOOLEAN NOT NULL,
    evidence_scope VARCHAR(60) NOT NULL,
    verified BOOLEAN NOT NULL,
    source VARCHAR(80) NOT NULL,
    linked_pmids_json JSONB NOT NULL,
    raw_metadata_json JSONB NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_clinical_trial_hospital_nct UNIQUE (hospital_id, nct_id),
    CONSTRAINT ck_clinical_trial_nct CHECK (nct_id ~ '^NCT[0-9]{8}$'),
    CONSTRAINT ck_clinical_trial_enrollment CHECK (enrollment IS NULL OR enrollment >= 0)
);

CREATE INDEX idx_clinical_trial_status
    ON clinical_trial_record(hospital_id, overall_status, fetched_at DESC);

CREATE TABLE project_clinical_trial (
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    clinical_trial_id UUID NOT NULL REFERENCES clinical_trial_record(id),
    search_task_id UUID NOT NULL REFERENCES literature_search_task(id) ON DELETE CASCADE,
    review_status VARCHAR(40) NOT NULL DEFAULT 'DISCOVERED',
    relevance_status VARCHAR(40) NOT NULL DEFAULT 'UNASSESSED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (search_task_id, clinical_trial_id)
);

CREATE INDEX idx_project_clinical_trial_project
    ON project_clinical_trial(hospital_id, project_id, created_at DESC);
