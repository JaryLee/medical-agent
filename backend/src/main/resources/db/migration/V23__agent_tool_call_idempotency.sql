CREATE TABLE ai_agent_tool_call (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    task_id UUID NOT NULL REFERENCES ai_agent_task(id) ON DELETE CASCADE,
    step_attempt_id UUID NOT NULL REFERENCES ai_agent_step_attempt(id),
    step_code VARCHAR(80) NOT NULL,
    attempt_no INTEGER NOT NULL,
    tool_call_key VARCHAR(120) NOT NULL,
    operation_key VARCHAR(64) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    result_json JSONB,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_agent_tool_call_attempt
        UNIQUE (
            hospital_id, task_id, step_code, attempt_no, tool_call_key
        ),
    CONSTRAINT ck_agent_tool_call_attempt_no CHECK (attempt_no > 0),
    CONSTRAINT ck_agent_tool_call_hashes CHECK (
        operation_key ~ '^[0-9a-f]{64}$'
        AND request_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_agent_tool_call_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'FAILED', 'SUPERSEDED')
    )
);

CREATE UNIQUE INDEX uk_agent_tool_call_active_operation
    ON ai_agent_tool_call(hospital_id, task_id, step_code, operation_key)
    WHERE status IN ('RUNNING', 'COMPLETED');

CREATE INDEX idx_agent_tool_call_attempt
    ON ai_agent_tool_call(step_attempt_id, tool_call_key);
