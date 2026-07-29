CREATE TABLE document_template_version (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    template_code VARCHAR(80) NOT NULL,
    template_name VARCHAR(200) NOT NULL,
    version_no INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    content_size BIGINT NOT NULL,
    placeholder_schema_version VARCHAR(80) NOT NULL,
    placeholders_json JSONB NOT NULL,
    validation_status VARCHAR(30) NOT NULL,
    validation_message VARCHAR(2000),
    created_by UUID NOT NULL REFERENCES platform_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    published_by UUID REFERENCES platform_user(id),
    published_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_document_template_version
        UNIQUE (hospital_id, template_code, version_no),
    CONSTRAINT ck_document_template_status
        CHECK (status IN ('VALIDATED','PUBLISHED','ARCHIVED')),
    CONSTRAINT ck_document_template_validation
        CHECK (validation_status IN ('VALID','INVALID')),
    CONSTRAINT ck_document_template_publish_state CHECK (
        (status = 'PUBLISHED' AND published_by IS NOT NULL AND published_at IS NOT NULL)
        OR status <> 'PUBLISHED'
    )
);

CREATE UNIQUE INDEX uk_document_template_published
    ON document_template_version(hospital_id, template_code)
    WHERE status = 'PUBLISHED';

CREATE INDEX idx_document_template_history
    ON document_template_version(hospital_id, template_code, version_no DESC);

CREATE TABLE document_export_record (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    agent_task_id UUID NOT NULL REFERENCES ai_agent_task(id) ON DELETE CASCADE,
    protocol_id UUID NOT NULL REFERENCES research_protocol(id),
    review_task_id UUID NOT NULL REFERENCES research_review_task(id),
    template_version_id UUID NOT NULL REFERENCES document_template_version(id),
    citation_style_code VARCHAR(80) NOT NULL,
    citation_style_version VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    requested_by UUID NOT NULL REFERENCES platform_user(id),
    confirmed_at TIMESTAMPTZ NOT NULL,
    protocol_snapshot_sha256 CHAR(64) NOT NULL,
    citation_snapshot_sha256 CHAR(64) NOT NULL,
    citation_count INTEGER NOT NULL,
    object_key VARCHAR(500),
    file_name VARCHAR(255),
    content_type VARCHAR(150),
    content_sha256 CHAR(64),
    content_size BIGINT,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(100),
    error_message VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_document_export_agent_task UNIQUE (agent_task_id),
    CONSTRAINT ck_document_export_status
        CHECK (status IN ('COMPLETED','FAILED')),
    CONSTRAINT ck_document_export_result CHECK (
        (status = 'COMPLETED'
            AND object_key IS NOT NULL
            AND file_name IS NOT NULL
            AND content_type IS NOT NULL
            AND content_sha256 IS NOT NULL
            AND content_size IS NOT NULL
            AND completed_at IS NOT NULL
            AND error_code IS NULL
            AND error_message IS NULL)
        OR
        (status = 'FAILED'
            AND error_code IS NOT NULL
            AND error_message IS NOT NULL)
    )
);

CREATE INDEX idx_document_export_project
    ON document_export_record(hospital_id, project_id, created_at DESC);
