CREATE TABLE ai_model_call_log (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id),
    task_id UUID NOT NULL REFERENCES ai_agent_task(id) ON DELETE CASCADE,
    step_code VARCHAR(80) NOT NULL,
    attempt_no INTEGER NOT NULL,
    provider VARCHAR(80) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    input_schema_version VARCHAR(80) NOT NULL,
    output_schema_version VARCHAR(80) NOT NULL,
    input_sha256 CHAR(64) NOT NULL,
    output_sha256 CHAR(64),
    input_snapshot_json JSONB,
    output_snapshot_json JSONB,
    raw_payload_object_key VARCHAR(500),
    payload_purged_at TIMESTAMPTZ,
    status VARCHAR(40) NOT NULL,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    payload_retention_until TIMESTAMPTZ,
    metadata_retention_until TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_model_call_status CHECK (
        status IN ('REQUESTED', 'SUCCEEDED', 'FAILED', 'LEGACY_UNVERIFIED')
    ),
    CONSTRAINT ck_model_call_attempt CHECK (attempt_no > 0),
    CONSTRAINT ck_model_call_payload_purge CHECK (
        payload_purged_at IS NULL OR (
            input_snapshot_json IS NULL
            AND output_snapshot_json IS NULL
            AND raw_payload_object_key IS NULL
        )
    )
);

CREATE INDEX idx_model_call_task
    ON ai_model_call_log(hospital_id, task_id, started_at, id);
CREATE INDEX idx_model_call_retention
    ON ai_model_call_log(status, payload_retention_until, metadata_retention_until);

INSERT INTO ai_model_call_log (
    id, hospital_id, project_id, task_id, step_code, attempt_no,
    provider, model_name, prompt_version,
    input_schema_version, output_schema_version,
    input_sha256, output_sha256, input_snapshot_json, output_snapshot_json,
    status, started_at, completed_at, metadata_retention_until
)
SELECT DISTINCT ON (step.model_call_id)
    step.model_call_id,
    step.hospital_id,
    task.project_id,
    step.task_id,
    step.step_code,
    GREATEST(step.attempt_no, 1),
    'legacy',
    'unknown',
    COALESCE(step.prompt_version, 'legacy/unknown'),
    step.input_schema_version,
    step.output_schema_version,
    encode(sha256(convert_to(step.input_json::text, 'UTF8')), 'hex'),
    CASE WHEN step.output_json IS NULL THEN NULL
         ELSE encode(sha256(convert_to(step.output_json::text, 'UTF8')), 'hex') END,
    jsonb_build_object(
        'legacy', true,
        'inputAvailableInStepRun', true
    ),
    jsonb_build_object(
        'legacy', true,
        'outputAvailableInStepRun', step.output_json IS NOT NULL
    ),
    'LEGACY_UNVERIFIED',
    step.started_at,
    step.completed_at,
    step.started_at + INTERVAL '3 years'
FROM ai_agent_step_run step
JOIN ai_agent_task task ON task.id = step.task_id
WHERE step.model_call_id IS NOT NULL
ORDER BY step.model_call_id, step.started_at;

ALTER TABLE ai_agent_step_run
    ADD CONSTRAINT fk_agent_step_model_call
    FOREIGN KEY (model_call_id) REFERENCES ai_model_call_log(id);

UPDATE ai_agent_task
SET output_json = output_json || jsonb_build_object(
        'candidateSetId', gen_random_uuid()::text,
        'candidateSetHash',
        encode(sha256(convert_to((output_json -> 'directions')::text, 'UTF8')), 'hex'),
        'candidateSetSchemaVersion', 'direction-candidates/v1'
    ),
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE current_step = 'STEP_05_CONFIRM_DIRECTION'
  AND status = 'WAITING_CONFIRMATION'
  AND jsonb_typeof(output_json -> 'directions') = 'array'
  AND jsonb_array_length(output_json -> 'directions') > 0
  AND NOT (output_json ? 'candidateSetId');
