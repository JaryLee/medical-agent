CREATE TABLE research_review_task (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    agent_task_id UUID NOT NULL REFERENCES ai_agent_task(id) ON DELETE CASCADE,
    protocol_id UUID NOT NULL REFERENCES research_protocol(id) ON DELETE CASCADE,
    strobe_check_task_id UUID NOT NULL
        REFERENCES strobe_completeness_check_task(id) ON DELETE CASCADE,
    status VARCHAR(40) NOT NULL,
    submitted_by UUID NOT NULL REFERENCES platform_user(id),
    submitted_at TIMESTAMPTZ NOT NULL,
    expert_reviewer_id UUID REFERENCES platform_user(id),
    expert_decision VARCHAR(40),
    expert_summary VARCHAR(2000),
    expert_decided_at TIMESTAMPTZ,
    owner_confirmed_by UUID REFERENCES platform_user(id),
    owner_confirmed_at TIMESTAMPTZ,
    sections_locked BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_research_review_agent_task UNIQUE (agent_task_id),
    CONSTRAINT ck_research_review_status CHECK (
        status IN (
            'WAITING_EXPERT_REVIEW','EXPERT_APPROVED',
            'REVISION_REQUIRED','APPROVED'
        )
    ),
    CONSTRAINT ck_research_review_decision CHECK (
        expert_decision IS NULL
        OR expert_decision IN ('APPROVE','RETURN_FOR_REVISION')
    ),
    CONSTRAINT ck_research_review_state CHECK (
        (status = 'WAITING_EXPERT_REVIEW'
            AND expert_reviewer_id IS NULL
            AND expert_decision IS NULL
            AND owner_confirmed_by IS NULL
            AND sections_locked = FALSE)
        OR
        (status IN ('EXPERT_APPROVED','REVISION_REQUIRED')
            AND expert_reviewer_id IS NOT NULL
            AND expert_decision IS NOT NULL
            AND expert_decided_at IS NOT NULL
            AND owner_confirmed_by IS NULL
            AND sections_locked = FALSE)
        OR
        (status = 'APPROVED'
            AND expert_decision = 'APPROVE'
            AND owner_confirmed_by IS NOT NULL
            AND owner_confirmed_at IS NOT NULL
            AND sections_locked = TRUE)
    )
);

CREATE INDEX idx_research_review_project
    ON research_review_task(hospital_id, project_id, submitted_at DESC);

CREATE TABLE research_review_comment (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    review_task_id UUID NOT NULL
        REFERENCES research_review_task(id) ON DELETE CASCADE,
    protocol_section_id UUID REFERENCES research_protocol_section(id),
    protocol_section_version_no INTEGER,
    strobe_item_result_id UUID
        REFERENCES strobe_completeness_check_item(id),
    comment_type VARCHAR(30) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    created_by UUID NOT NULL REFERENCES platform_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_research_review_comment_type CHECK (
        comment_type IN ('MEDICAL','STATISTICAL','REPORTING','GENERAL')
    ),
    CONSTRAINT ck_research_review_comment_target CHECK (
        (protocol_section_id IS NOT NULL
            AND protocol_section_version_no IS NOT NULL
            AND protocol_section_version_no > 0
            AND strobe_item_result_id IS NULL)
        OR
        (protocol_section_id IS NULL
            AND protocol_section_version_no IS NULL
            AND strobe_item_result_id IS NOT NULL)
    )
);

CREATE INDEX idx_research_review_comment_history
    ON research_review_comment(hospital_id, review_task_id, created_at, id);

CREATE TABLE research_review_action (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    review_task_id UUID NOT NULL
        REFERENCES research_review_task(id) ON DELETE CASCADE,
    action_type VARCHAR(40) NOT NULL,
    actor_user_id UUID REFERENCES platform_user(id),
    summary VARCHAR(2000),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_research_review_action_type CHECK (
        action_type IN (
            'REVIEW_OPENED','COMMENT_ADDED','EXPERT_APPROVED',
            'RETURNED_FOR_REVISION','OWNER_CONFIRMED'
        )
    )
);

CREATE INDEX idx_research_review_action_history
    ON research_review_action(hospital_id, review_task_id, occurred_at, id);
