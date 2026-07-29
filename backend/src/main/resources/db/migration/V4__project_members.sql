CREATE TABLE project_member (
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES platform_user(id),
    member_role VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT ck_project_member_role CHECK (member_role IN ('OWNER', 'EDITOR', 'VIEWER'))
);

CREATE INDEX idx_project_member_hospital_user
    ON project_member(hospital_id, user_id, project_id);
