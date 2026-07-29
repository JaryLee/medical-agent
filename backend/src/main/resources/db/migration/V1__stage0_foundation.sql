CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE hospital (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE research_project (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_code VARCHAR(64) NOT NULL,
    project_name VARCHAR(300) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_project_hospital_code UNIQUE (hospital_id, project_code)
);

CREATE TABLE ai_agent_task (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id),
    current_step VARCHAR(80) NOT NULL,
    status VARCHAR(40) NOT NULL,
    input_json JSONB NOT NULL,
    output_json JSONB,
    lease_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_task_hospital_status ON ai_agent_task(hospital_id, status);

CREATE TABLE ai_agent_step_run (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    task_id UUID NOT NULL REFERENCES ai_agent_task(id),
    step_code VARCHAR(80) NOT NULL,
    attempt_no INTEGER NOT NULL,
    status VARCHAR(40) NOT NULL,
    input_schema_version VARCHAR(80) NOT NULL,
    output_schema_version VARCHAR(80) NOT NULL,
    input_json JSONB NOT NULL,
    output_json JSONB,
    model_call_id UUID,
    prompt_version VARCHAR(80),
    tool_calls_json JSONB,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
    confirmed_by UUID,
    confirmed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_step_attempt UNIQUE (hospital_id, task_id, step_code, attempt_no)
);
