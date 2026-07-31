CREATE TABLE project_workspace_cursor (
    project_id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL,
    read_model_version BIGINT NOT NULL DEFAULT 1,
    latest_event_id BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_project_workspace_cursor_tenant
        UNIQUE (hospital_id, project_id),
    CONSTRAINT fk_project_workspace_cursor_project
        FOREIGN KEY (hospital_id, project_id)
        REFERENCES research_project(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_project_workspace_cursor_version
        CHECK (read_model_version > 0),
    CONSTRAINT ck_project_workspace_cursor_latest_event
        CHECK (latest_event_id IS NULL OR latest_event_id > 0)
);

CREATE TABLE project_read_model_event (
    id BIGSERIAL PRIMARY KEY,
    hospital_id UUID NOT NULL,
    project_id UUID NOT NULL,
    read_model_version BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL DEFAULT 'PROJECT_READ_MODEL_CHANGED',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_project_read_model_event_version
        UNIQUE (hospital_id, project_id, read_model_version),
    CONSTRAINT fk_project_read_model_event_project
        FOREIGN KEY (hospital_id, project_id)
        REFERENCES research_project(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_project_read_model_event_version
        CHECK (read_model_version > 0),
    CONSTRAINT ck_project_read_model_event_type
        CHECK (
            event_type IN (
                'PROJECT_READ_MODEL_CHANGED',
                'PROJECT_RESYNC_REQUIRED'
            )
        )
);

CREATE INDEX idx_project_read_model_event_replay
    ON project_read_model_event(hospital_id, project_id, id);

CREATE TABLE project_workspace_command (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL,
    project_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    action_code VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    expected_read_model_version BIGINT NOT NULL,
    result_read_model_version BIGINT,
    status VARCHAR(20) NOT NULL,
    response_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_project_workspace_command_scope
        UNIQUE (
            hospital_id, project_id, actor_user_id, idempotency_key
        ),
    CONSTRAINT uk_project_workspace_command_tenant
        UNIQUE (hospital_id, id),
    CONSTRAINT fk_project_workspace_command_project
        FOREIGN KEY (hospital_id, project_id)
        REFERENCES research_project(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_project_workspace_command_actor
        FOREIGN KEY (hospital_id, actor_user_id)
        REFERENCES platform_user(hospital_id, id),
    CONSTRAINT ck_project_workspace_command_key
        CHECK (
            char_length(idempotency_key) BETWEEN 16 AND 128
            AND idempotency_key !~ '[[:cntrl:]]'
        ),
    CONSTRAINT ck_project_workspace_command_hash
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_project_workspace_command_expected_version
        CHECK (expected_read_model_version > 0),
    CONSTRAINT ck_project_workspace_command_result_version
        CHECK (
            result_read_model_version IS NULL
            OR result_read_model_version > 0
        ),
    CONSTRAINT ck_project_workspace_command_status
        CHECK (status IN ('RUNNING', 'COMPLETED')),
    CONSTRAINT ck_project_workspace_command_state
        CHECK (
            (
                status = 'RUNNING'
                AND result_read_model_version IS NULL
                AND response_json IS NULL
                AND completed_at IS NULL
            )
            OR (
                status = 'COMPLETED'
                AND result_read_model_version IS NOT NULL
                AND response_json IS NOT NULL
                AND completed_at IS NOT NULL
            )
        )
);

CREATE INDEX idx_project_workspace_command_lookup
    ON project_workspace_command(
        hospital_id, project_id, actor_user_id, created_at DESC
    );

INSERT INTO project_workspace_cursor(
    project_id, hospital_id, read_model_version, updated_at
)
SELECT id, hospital_id, 1, created_at
FROM research_project;

INSERT INTO project_read_model_event(
    hospital_id, project_id, read_model_version, occurred_at
)
SELECT hospital_id, id, 1, created_at
FROM research_project
ORDER BY created_at, id;

UPDATE project_workspace_cursor cursor_row
SET latest_event_id = event_row.id
FROM project_read_model_event event_row
WHERE event_row.hospital_id = cursor_row.hospital_id
  AND event_row.project_id = cursor_row.project_id
  AND event_row.read_model_version = cursor_row.read_model_version;

CREATE FUNCTION initialize_project_workspace_cursor()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    created_event_id BIGINT;
BEGIN
    INSERT INTO project_workspace_cursor(
        project_id, hospital_id, read_model_version, updated_at
    )
    VALUES (NEW.id, NEW.hospital_id, 1, NEW.created_at);

    INSERT INTO project_read_model_event(
        hospital_id, project_id, read_model_version, occurred_at
    )
    VALUES (NEW.hospital_id, NEW.id, 1, NEW.created_at)
    RETURNING id INTO created_event_id;

    UPDATE project_workspace_cursor
    SET latest_event_id = created_event_id
    WHERE hospital_id = NEW.hospital_id
      AND project_id = NEW.id;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_research_project_workspace_initialize
AFTER INSERT ON research_project
FOR EACH ROW
EXECUTE FUNCTION initialize_project_workspace_cursor();

CREATE FUNCTION bump_project_workspace_cursor(
    target_hospital_id UUID,
    target_project_id UUID,
    changed_at TIMESTAMPTZ
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    next_version BIGINT;
    created_event_id BIGINT;
BEGIN
    INSERT INTO project_workspace_cursor(
        project_id, hospital_id, read_model_version, updated_at
    )
    VALUES (target_project_id, target_hospital_id, 1, changed_at)
    ON CONFLICT (project_id)
    DO UPDATE SET
        read_model_version =
            project_workspace_cursor.read_model_version + 1,
        updated_at = GREATEST(
            project_workspace_cursor.updated_at,
            EXCLUDED.updated_at
        )
    RETURNING read_model_version INTO next_version;

    INSERT INTO project_read_model_event(
        hospital_id, project_id, read_model_version, occurred_at
    )
    VALUES (
        target_hospital_id, target_project_id, next_version, changed_at
    )
    RETURNING id INTO created_event_id;

    UPDATE project_workspace_cursor
    SET latest_event_id = created_event_id
    WHERE hospital_id = target_hospital_id
      AND project_id = target_project_id;
END
$$;

CREATE FUNCTION notify_project_workspace_from_agent_event()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_project_id UUID;
BEGIN
    SELECT project_id
    INTO target_project_id
    FROM ai_agent_task
    WHERE hospital_id = NEW.hospital_id
      AND id = NEW.task_id;

    IF target_project_id IS NULL THEN
        RAISE EXCEPTION
            'agent event % has no tenant-consistent task', NEW.id;
    END IF;

    PERFORM bump_project_workspace_cursor(
        NEW.hospital_id, target_project_id, NEW.occurred_at
    );
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_agent_event_project_workspace
AFTER INSERT ON ai_agent_event
FOR EACH ROW
EXECUTE FUNCTION notify_project_workspace_from_agent_event();

CREATE FUNCTION notify_project_workspace_from_project_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.project_name IS DISTINCT FROM OLD.project_name THEN
        PERFORM bump_project_workspace_cursor(
            NEW.hospital_id, NEW.id, clock_timestamp()
        );
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_research_project_workspace_update
AFTER UPDATE OF project_name ON research_project
FOR EACH ROW
EXECUTE FUNCTION notify_project_workspace_from_project_update();
