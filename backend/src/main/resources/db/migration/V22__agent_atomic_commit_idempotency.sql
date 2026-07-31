ALTER TABLE ai_agent_step_run
    ADD COLUMN step_attempt_id UUID;

UPDATE ai_agent_step_run step
SET step_attempt_id = attempt.id
FROM ai_agent_step_attempt attempt
WHERE attempt.hospital_id = step.hospital_id
  AND attempt.task_id = step.task_id
  AND attempt.step_code = step.step_code
  AND attempt.attempt_no = GREATEST(step.attempt_no, 1);

ALTER TABLE ai_agent_step_run
    ADD CONSTRAINT fk_agent_step_run_attempt
    FOREIGN KEY (step_attempt_id) REFERENCES ai_agent_step_attempt(id);

CREATE INDEX idx_agent_step_run_attempt
    ON ai_agent_step_run(step_attempt_id)
    WHERE step_attempt_id IS NOT NULL;

ALTER TABLE ai_agent_event
    ADD COLUMN event_key VARCHAR(200);

UPDATE ai_agent_event
SET event_key = 'legacy:' || id;

ALTER TABLE ai_agent_event
    ALTER COLUMN event_key SET NOT NULL;

CREATE UNIQUE INDEX uk_agent_event_stable_key
    ON ai_agent_event(hospital_id, task_id, event_key);
