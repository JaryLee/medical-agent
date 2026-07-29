CREATE TABLE observational_design_recommendation_task (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    agent_task_id UUID NOT NULL REFERENCES ai_agent_task(id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    recommended_study_type VARCHAR(40),
    primary_outcome_candidate TEXT,
    ready_for_protocol_draft BOOLEAN,
    alternative_count INTEGER,
    unresolved_items_json JSONB,
    required_confirmations_json JSONB,
    input_sha256 CHAR(64) NOT NULL,
    algorithm_version VARCHAR(80) NOT NULL,
    result_json JSONB,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_observational_design_recommendation_status
        CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    CONSTRAINT ck_observational_design_recommendation_count
        CHECK (alternative_count IS NULL OR alternative_count >= 0)
);

CREATE INDEX idx_observational_design_recommendation_agent
    ON observational_design_recommendation_task(
        hospital_id,agent_task_id,started_at DESC
    );

CREATE TABLE observational_design_alternative (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    recommendation_task_id UUID NOT NULL
        REFERENCES observational_design_recommendation_task(id) ON DELETE CASCADE,
    rank_no INTEGER NOT NULL,
    study_type VARCHAR(40) NOT NULL,
    score INTEGER NOT NULL,
    feasibility_status VARCHAR(40) NOT NULL,
    rationale TEXT NOT NULL,
    required_fields_json JSONB NOT NULL,
    missing_fields_json JSONB NOT NULL,
    bias_risks_json JSONB NOT NULL,
    evidence_considerations_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_observational_design_alternative_type
        UNIQUE (recommendation_task_id,study_type),
    CONSTRAINT uk_observational_design_alternative_rank
        UNIQUE (recommendation_task_id,rank_no),
    CONSTRAINT ck_observational_design_alternative_score
        CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_observational_design_alternative_feasibility
        CHECK (feasibility_status IN ('READY','NEEDS_CLARIFICATION'))
);

CREATE INDEX idx_observational_design_alternative_rank
    ON observational_design_alternative(hospital_id,recommendation_task_id,rank_no);
