CREATE TABLE project_file (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id),
    original_name VARCHAR(255) NOT NULL,
    object_key VARCHAR(700) NOT NULL UNIQUE,
    content_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    security_status VARCHAR(50) NOT NULL,
    matched_rules VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_project_file_hospital_project
    ON project_file(hospital_id, project_id, created_at);
