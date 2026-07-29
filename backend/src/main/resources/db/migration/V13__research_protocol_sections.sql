CREATE TABLE research_protocol (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    agent_task_id UUID NOT NULL REFERENCES ai_agent_task(id),
    status VARCHAR(30) NOT NULL,
    study_type VARCHAR(40) NOT NULL,
    title VARCHAR(500) NOT NULL,
    schema_version VARCHAR(80) NOT NULL,
    generator_version VARCHAR(80) NOT NULL,
    input_sha256 CHAR(64) NOT NULL,
    issues_to_confirm_json JSONB NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_research_protocol_agent_task UNIQUE (agent_task_id),
    CONSTRAINT ck_research_protocol_status
        CHECK (status IN ('DRAFT','WAITING_REVIEW','APPROVED','ARCHIVED')),
    CONSTRAINT ck_research_protocol_study_type
        CHECK (study_type IN ('CROSS_SECTIONAL','COHORT','CASE_CONTROL'))
);

CREATE INDEX idx_research_protocol_project
    ON research_protocol(hospital_id,project_id,created_at DESC);

CREATE TABLE research_protocol_section (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    protocol_id UUID NOT NULL REFERENCES research_protocol(id) ON DELETE CASCADE,
    section_code VARCHAR(80) NOT NULL,
    title VARCHAR(200) NOT NULL,
    sort_order INTEGER NOT NULL,
    current_version_no INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_research_protocol_section_code
        UNIQUE (protocol_id,section_code),
    CONSTRAINT uk_research_protocol_section_order
        UNIQUE (protocol_id,sort_order),
    CONSTRAINT ck_research_protocol_section_order
        CHECK (sort_order > 0),
    CONSTRAINT ck_research_protocol_section_version
        CHECK (current_version_no > 0),
    CONSTRAINT ck_research_protocol_section_status
        CHECK (status IN ('DRAFT','LOCKED','SUPERSEDED'))
);

CREATE INDEX idx_research_protocol_section_order
    ON research_protocol_section(hospital_id,protocol_id,sort_order);

CREATE TABLE research_protocol_section_version (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    section_id UUID NOT NULL
        REFERENCES research_protocol_section(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_format VARCHAR(30) NOT NULL,
    origin VARCHAR(40) NOT NULL,
    evidence_status VARCHAR(40) NOT NULL,
    source_identifiers_json JSONB NOT NULL,
    issues_to_confirm_json JSONB NOT NULL,
    change_reason VARCHAR(80) NOT NULL,
    created_by UUID REFERENCES platform_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_research_protocol_section_version
        UNIQUE (section_id,version_no),
    CONSTRAINT ck_research_protocol_section_version_no
        CHECK (version_no > 0),
    CONSTRAINT ck_research_protocol_section_format
        CHECK (content_format IN ('MARKDOWN','PLAIN_TEXT')),
    CONSTRAINT ck_research_protocol_section_origin
        CHECK (origin IN ('AGENT_DETERMINISTIC','AGENT_MODEL','HUMAN')),
    CONSTRAINT ck_research_protocol_section_evidence
        CHECK (evidence_status IN (
            'DOCTOR_CONFIRMED_INPUT','VERIFIED_METADATA','ABSTRACT_ONLY',
            'NEEDS_EXPERT_REVIEW','NOT_APPLICABLE'
        ))
);

CREATE INDEX idx_research_protocol_section_version_history
    ON research_protocol_section_version(
        hospital_id,section_id,version_no DESC
    );
