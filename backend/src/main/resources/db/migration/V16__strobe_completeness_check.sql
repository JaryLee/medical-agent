CREATE TABLE strobe_completeness_check_task (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    agent_task_id UUID NOT NULL REFERENCES ai_agent_task(id),
    protocol_id UUID NOT NULL REFERENCES research_protocol(id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL,
    study_type VARCHAR(40) NOT NULL,
    total_item_count INTEGER NOT NULL,
    covered_count INTEGER NOT NULL,
    partially_covered_count INTEGER NOT NULL,
    missing_count INTEGER NOT NULL,
    not_applicable_count INTEGER NOT NULL,
    needs_expert_review_count INTEGER NOT NULL,
    input_sha256 CHAR(64) NOT NULL,
    checker_version VARCHAR(100) NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_strobe_check_agent UNIQUE (agent_task_id),
    CONSTRAINT uk_strobe_check_protocol UNIQUE (protocol_id),
    CONSTRAINT ck_strobe_check_status CHECK (status IN ('COMPLETED')),
    CONSTRAINT ck_strobe_check_study_type CHECK (
        study_type IN ('CROSS_SECTIONAL','COHORT','CASE_CONTROL')
    ),
    CONSTRAINT ck_strobe_check_counts CHECK (
        total_item_count = 22
        AND covered_count >= 0
        AND partially_covered_count >= 0
        AND missing_count >= 0
        AND not_applicable_count >= 0
        AND needs_expert_review_count >= 0
        AND covered_count + partially_covered_count + missing_count
            + not_applicable_count + needs_expert_review_count = total_item_count
    )
);

CREATE INDEX idx_strobe_check_project
    ON strobe_completeness_check_task(hospital_id,project_id,created_at DESC);

CREATE TABLE strobe_completeness_check_item (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    check_task_id UUID NOT NULL
        REFERENCES strobe_completeness_check_task(id) ON DELETE CASCADE,
    item_code VARCHAR(20) NOT NULL,
    section_group VARCHAR(40) NOT NULL,
    requirement_summary TEXT NOT NULL,
    study_type VARCHAR(40) NOT NULL,
    check_status VARCHAR(40) NOT NULL,
    mapped_section_codes_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_snippets_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    message TEXT NOT NULL,
    suggestion TEXT NOT NULL,
    requires_expert_review BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_strobe_check_item UNIQUE (check_task_id,item_code),
    CONSTRAINT ck_strobe_check_item_code CHECK (
        item_code ~ '^STROBE-(0[1-9]|1[0-9]|2[0-2])$'
    ),
    CONSTRAINT ck_strobe_check_item_study_type CHECK (
        study_type IN ('CROSS_SECTIONAL','COHORT','CASE_CONTROL')
    ),
    CONSTRAINT ck_strobe_check_item_status CHECK (
        check_status IN (
            'COVERED','PARTIALLY_COVERED','MISSING',
            'NOT_APPLICABLE','NEEDS_EXPERT_REVIEW'
        )
    )
);

CREATE INDEX idx_strobe_check_item_order
    ON strobe_completeness_check_item(
        hospital_id,check_task_id,item_code
    );
