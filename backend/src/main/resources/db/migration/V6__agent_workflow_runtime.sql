ALTER TABLE ai_agent_task
    ADD COLUMN created_by UUID REFERENCES platform_user(id),
    ADD COLUMN idempotency_key VARCHAR(100),
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN timeout_at TIMESTAMPTZ,
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN last_error_code VARCHAR(80),
    ADD COLUMN last_error_message VARCHAR(500);

CREATE UNIQUE INDEX uk_agent_task_idempotency
    ON ai_agent_task(hospital_id, created_by, idempotency_key)
    WHERE created_by IS NOT NULL AND idempotency_key IS NOT NULL;

CREATE TABLE ai_agent_event (
    id BIGSERIAL PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    task_id UUID NOT NULL REFERENCES ai_agent_task(id) ON DELETE CASCADE,
    event_type VARCHAR(80) NOT NULL,
    step_code VARCHAR(80),
    payload_json JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_event_task_replay
    ON ai_agent_event(hospital_id, task_id, id);
