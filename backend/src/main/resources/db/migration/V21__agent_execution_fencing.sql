DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM ai_agent_task WHERE status = 'RUNNING') THEN
        RAISE EXCEPTION
            'V21 requires all legacy workers to be stopped and no RUNNING tasks';
    END IF;
END
$$;

ALTER TABLE ai_agent_task
    ADD COLUMN execution_token UUID,
    ADD COLUMN lease_owner VARCHAR(160),
    ADD COLUMN lease_acquired_at TIMESTAMPTZ,
    ADD COLUMN heartbeat_at TIMESTAMPTZ,
    ADD COLUMN current_step_attempt_id UUID;

CREATE UNIQUE INDEX uk_agent_task_execution_token
    ON ai_agent_task(execution_token)
    WHERE execution_token IS NOT NULL;

CREATE TABLE ai_agent_step_attempt (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    task_id UUID NOT NULL REFERENCES ai_agent_task(id) ON DELETE CASCADE,
    step_code VARCHAR(80) NOT NULL,
    attempt_no INTEGER NOT NULL,
    execution_token UUID NOT NULL,
    lease_owner VARCHAR(160) NOT NULL,
    status VARCHAR(40) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    CONSTRAINT uk_agent_step_attempt_no
        UNIQUE (hospital_id, task_id, step_code, attempt_no),
    CONSTRAINT ck_agent_step_attempt_no CHECK (attempt_no > 0),
    CONSTRAINT ck_agent_step_attempt_status CHECK (
        status IN (
            'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED',
            'LEASE_LOST', 'LEGACY_RECORDED'
        )
    )
);

CREATE INDEX idx_agent_step_attempt_task
    ON ai_agent_step_attempt(hospital_id, task_id, step_code, attempt_no);
CREATE INDEX idx_agent_step_attempt_running
    ON ai_agent_step_attempt(status, heartbeat_at)
    WHERE status = 'RUNNING';

INSERT INTO ai_agent_step_attempt (
    id, hospital_id, task_id, step_code, attempt_no, execution_token,
    lease_owner, status, started_at, heartbeat_at, completed_at,
    error_code, error_message
)
SELECT
    step.id,
    step.hospital_id,
    step.task_id,
    step.step_code,
    GREATEST(step.attempt_no, 1),
    gen_random_uuid(),
    'legacy',
    'LEGACY_RECORDED',
    step.started_at,
    step.completed_at,
    step.completed_at,
    step.error_code,
    step.error_message
FROM ai_agent_step_run step
ON CONFLICT (hospital_id, task_id, step_code, attempt_no) DO NOTHING;

ALTER TABLE ai_agent_task
    ADD CONSTRAINT fk_agent_task_current_step_attempt
    FOREIGN KEY (current_step_attempt_id) REFERENCES ai_agent_step_attempt(id);

