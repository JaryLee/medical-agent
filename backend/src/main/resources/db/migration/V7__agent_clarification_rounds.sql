CREATE TABLE ai_agent_clarification_round (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    task_id UUID NOT NULL REFERENCES ai_agent_task(id) ON DELETE CASCADE,
    round_no INTEGER NOT NULL,
    source_step VARCHAR(80) NOT NULL,
    questions_json JSONB NOT NULL,
    answers_json JSONB NOT NULL,
    submitted_by UUID NOT NULL REFERENCES platform_user(id),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_clarification_round UNIQUE (task_id, round_no),
    CONSTRAINT ck_agent_clarification_round_positive CHECK (round_no > 0)
);

CREATE INDEX idx_agent_clarification_round_history
    ON ai_agent_clarification_round(hospital_id, task_id, round_no);
