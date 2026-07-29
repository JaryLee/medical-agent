CREATE TABLE statistical_analysis_draft (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    agent_task_id UUID NOT NULL REFERENCES ai_agent_task(id),
    protocol_id UUID NOT NULL REFERENCES research_protocol(id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL,
    study_type VARCHAR(40) NOT NULL,
    primary_outcome TEXT NOT NULL,
    outcome_type_status VARCHAR(40) NOT NULL,
    parameter_count INTEGER NOT NULL,
    input_sha256 CHAR(64) NOT NULL,
    generator_version VARCHAR(80) NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_statistical_analysis_draft_agent UNIQUE (agent_task_id),
    CONSTRAINT uk_statistical_analysis_draft_protocol UNIQUE (protocol_id),
    CONSTRAINT ck_statistical_analysis_draft_status
        CHECK (status IN ('DRAFT','WAITING_EXPERT_REVIEW','APPROVED')),
    CONSTRAINT ck_statistical_analysis_draft_study_type
        CHECK (study_type IN ('CROSS_SECTIONAL','COHORT','CASE_CONTROL')),
    CONSTRAINT ck_statistical_analysis_draft_outcome_type
        CHECK (outcome_type_status IN ('NEEDS_EXPERT_CONFIRMATION','CONFIRMED')),
    CONSTRAINT ck_statistical_analysis_draft_parameter_count
        CHECK (parameter_count > 0)
);

CREATE INDEX idx_statistical_analysis_draft_project
    ON statistical_analysis_draft(hospital_id,project_id,created_at DESC);

CREATE TABLE sample_size_parameter_requirement (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    statistical_draft_id UUID NOT NULL
        REFERENCES statistical_analysis_draft(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL,
    parameter_code VARCHAR(80) NOT NULL,
    label VARCHAR(200) NOT NULL,
    is_required BOOLEAN NOT NULL,
    value_status VARCHAR(40) NOT NULL,
    value_text VARCHAR(500),
    unit VARCHAR(80),
    rationale TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_sample_size_parameter_code
        UNIQUE (statistical_draft_id,parameter_code),
    CONSTRAINT uk_sample_size_parameter_order
        UNIQUE (statistical_draft_id,sort_order),
    CONSTRAINT ck_sample_size_parameter_order CHECK (sort_order > 0),
    CONSTRAINT ck_sample_size_parameter_status
        CHECK (value_status IN ('MISSING_NEEDS_INPUT','PROVIDED_UNVERIFIED','CONFIRMED'))
);

CREATE INDEX idx_sample_size_parameter_requirement_order
    ON sample_size_parameter_requirement(
        hospital_id,statistical_draft_id,sort_order
    );
