ALTER TABLE research_review_task
    DROP CONSTRAINT ck_research_review_state,
    DROP CONSTRAINT ck_research_review_status;

ALTER TABLE research_review_task
    ADD COLUMN round_no INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN review_content_sha256 CHAR(64),
    ADD COLUMN legacy_review BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN statistical_reviewer_id UUID REFERENCES platform_user(id),
    ADD COLUMN statistical_decision VARCHAR(40),
    ADD COLUMN statistical_summary VARCHAR(2000),
    ADD COLUMN statistical_decided_at TIMESTAMPTZ;

UPDATE research_review_task review
SET review_content_sha256 = encode(
        sha256(convert_to(task.output_json::text, 'UTF8')),
        'hex'
    ),
    legacy_review = TRUE,
    status = 'SUPERSEDED'
FROM ai_agent_task task
WHERE task.id = review.agent_task_id;

ALTER TABLE research_review_task
    ALTER COLUMN review_content_sha256 SET NOT NULL,
    ADD CONSTRAINT ck_research_review_round CHECK (round_no > 0),
    ADD CONSTRAINT ck_research_review_content_hash
        CHECK (review_content_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_research_review_status_v2 CHECK (
        status IN (
            'WAITING_EXPERT_REVIEW','EXPERT_APPROVED',
            'REVISION_REQUIRED','APPROVED','SUPERSEDED'
        )
    ),
    ADD CONSTRAINT ck_research_review_statistical_decision CHECK (
        statistical_decision IS NULL
        OR statistical_decision IN ('APPROVE','RETURN_FOR_REVISION')
    ),
    ADD CONSTRAINT ck_research_review_independent_accounts CHECK (
        expert_reviewer_id IS NULL
        OR statistical_reviewer_id IS NULL
        OR expert_reviewer_id <> statistical_reviewer_id
    ),
    ADD CONSTRAINT ck_research_review_state_v2 CHECK (
        status = 'SUPERSEDED'
        OR (
            status = 'WAITING_EXPERT_REVIEW'
            AND owner_confirmed_by IS NULL
            AND sections_locked = FALSE
            AND coalesce(expert_decision, 'APPROVE') = 'APPROVE'
            AND coalesce(statistical_decision, 'APPROVE') = 'APPROVE'
        )
        OR (
            status = 'REVISION_REQUIRED'
            AND owner_confirmed_by IS NULL
            AND sections_locked = FALSE
            AND (
                expert_decision = 'RETURN_FOR_REVISION'
                OR statistical_decision = 'RETURN_FOR_REVISION'
            )
        )
        OR (
            status = 'EXPERT_APPROVED'
            AND expert_reviewer_id IS NOT NULL
            AND expert_decision = 'APPROVE'
            AND expert_decided_at IS NOT NULL
            AND submitted_by <> expert_reviewer_id
            AND statistical_reviewer_id IS NOT NULL
            AND statistical_decision = 'APPROVE'
            AND statistical_decided_at IS NOT NULL
            AND submitted_by <> statistical_reviewer_id
            AND owner_confirmed_by IS NULL
            AND legacy_review = FALSE
            AND sections_locked = FALSE
        )
        OR (
            status = 'APPROVED'
            AND expert_reviewer_id IS NOT NULL
            AND expert_decision = 'APPROVE'
            AND submitted_by <> expert_reviewer_id
            AND statistical_reviewer_id IS NOT NULL
            AND statistical_decision = 'APPROVE'
            AND submitted_by <> statistical_reviewer_id
            AND owner_confirmed_by IS NOT NULL
            AND owner_confirmed_by <> expert_reviewer_id
            AND owner_confirmed_by <> statistical_reviewer_id
            AND owner_confirmed_at IS NOT NULL
            AND legacy_review = FALSE
            AND sections_locked = TRUE
        )
    );

ALTER TABLE research_review_comment
    ADD COLUMN review_round_no INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN responsibility VARCHAR(40) NOT NULL DEFAULT 'LEGACY';

ALTER TABLE research_review_comment
    ADD CONSTRAINT ck_research_review_comment_round
        CHECK (review_round_no > 0),
    ADD CONSTRAINT ck_research_review_comment_responsibility
        CHECK (
            responsibility IN (
                'MEDICAL_REVIEW','STATISTICAL_REVIEW','LEGACY'
            )
        );

ALTER TABLE research_review_action
    DROP CONSTRAINT ck_research_review_action_type;

ALTER TABLE research_review_action
    ADD COLUMN review_round_no INTEGER NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_research_review_action_round
        CHECK (review_round_no > 0),
    ADD CONSTRAINT ck_research_review_action_type_v2 CHECK (
        action_type IN (
            'REVIEW_OPENED','REVIEW_SUPERSEDED','COMMENT_ADDED',
            'MEDICAL_REVIEW_APPROVED','STATISTICAL_REVIEW_APPROVED',
            'MEDICAL_REVIEW_RETURNED','STATISTICAL_REVIEW_RETURNED',
            'EXPERT_APPROVED','RETURNED_FOR_REVISION','OWNER_CONFIRMED'
        )
    );

CREATE TABLE research_review_decision (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    review_task_id UUID NOT NULL
        REFERENCES research_review_task(id) ON DELETE CASCADE,
    review_round_no INTEGER NOT NULL,
    responsibility VARCHAR(40) NOT NULL,
    reviewer_id UUID NOT NULL REFERENCES platform_user(id),
    decision VARCHAR(40) NOT NULL,
    summary VARCHAR(2000) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_research_review_decision
        UNIQUE (review_task_id,review_round_no,responsibility),
    CONSTRAINT ck_research_review_decision_round
        CHECK (review_round_no > 0),
    CONSTRAINT ck_research_review_decision_responsibility
        CHECK (responsibility IN ('MEDICAL_REVIEW','STATISTICAL_REVIEW')),
    CONSTRAINT ck_research_review_decision_value
        CHECK (decision IN ('APPROVE','RETURN_FOR_REVISION')),
    CONSTRAINT ck_research_review_decision_hash
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_research_review_decision_history
    ON research_review_decision(
        hospital_id,review_task_id,review_round_no,responsibility
    );

CREATE UNIQUE INDEX uk_research_review_decision_hospital_id
    ON research_review_decision(hospital_id,id);

ALTER TABLE research_review_task
    ADD CONSTRAINT fk_review_statistical_reviewer_tenant
    FOREIGN KEY (hospital_id, statistical_reviewer_id)
    REFERENCES platform_user(hospital_id, id);

ALTER TABLE research_review_decision
    ADD CONSTRAINT fk_review_decision_task_tenant
    FOREIGN KEY (hospital_id, review_task_id)
    REFERENCES research_review_task(hospital_id, id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_review_decision_reviewer_tenant
    FOREIGN KEY (hospital_id, reviewer_id)
    REFERENCES platform_user(hospital_id, id);

CREATE OR REPLACE FUNCTION reject_research_review_decision_mutation()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION
        'research_review_decision is append-only; % is forbidden',
        TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_research_review_decision_immutable
BEFORE UPDATE OR DELETE ON research_review_decision
FOR EACH ROW EXECUTE FUNCTION reject_research_review_decision_mutation();
