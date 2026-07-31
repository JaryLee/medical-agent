ALTER TABLE ai_model_call_log
    ADD COLUMN logical_model_type VARCHAR(40) NOT NULL DEFAULT 'RESEARCH_FAST',
    ADD COLUMN route_policy_version VARCHAR(80) NOT NULL DEFAULT 'legacy-single-route/v1',
    ADD COLUMN route_reason VARCHAR(80) NOT NULL DEFAULT 'LEGACY_OR_DEFAULT',
    ADD COLUMN provider_request_id VARCHAR(200),
    ADD COLUMN usage_source VARCHAR(40) NOT NULL DEFAULT 'NOT_AVAILABLE',
    ADD COLUMN input_tokens BIGINT,
    ADD COLUMN cached_input_tokens BIGINT,
    ADD COLUMN output_tokens BIGINT,
    ADD COLUMN total_tokens BIGINT,
    ADD COLUMN price_version VARCHAR(80),
    ADD COLUMN price_currency CHAR(3),
    ADD COLUMN reserved_cost_micros BIGINT,
    ADD COLUMN estimated_cost_micros BIGINT,
    ADD COLUMN cost_status VARCHAR(40) NOT NULL DEFAULT 'UNPRICED',
    ADD CONSTRAINT ck_model_call_logical_type CHECK (
        logical_model_type IN (
            'RESEARCH_FAST','RESEARCH_STANDARD','RESEARCH_REASONING','RESEARCH_REVIEW'
        )
    ),
    ADD CONSTRAINT ck_model_call_usage_source CHECK (
        usage_source IN ('NOT_AVAILABLE','PROVIDER_REPORTED','SYNTHETIC_TEST')
    ),
    ADD CONSTRAINT ck_model_call_tokens_non_negative CHECK (
        (input_tokens IS NULL OR input_tokens >= 0)
        AND (cached_input_tokens IS NULL OR cached_input_tokens >= 0)
        AND (output_tokens IS NULL OR output_tokens >= 0)
        AND (total_tokens IS NULL OR total_tokens >= 0)
        AND (
            cached_input_tokens IS NULL
            OR input_tokens IS NULL
            OR cached_input_tokens <= input_tokens
        )
        AND (
            total_tokens IS NULL
            OR input_tokens IS NULL
            OR output_tokens IS NULL
            OR total_tokens >= input_tokens + output_tokens
        )
    ),
    ADD CONSTRAINT ck_model_call_usage_state CHECK (
        (
            usage_source = 'NOT_AVAILABLE'
            AND input_tokens IS NULL
            AND cached_input_tokens IS NULL
            AND output_tokens IS NULL
            AND total_tokens IS NULL
        )
        OR usage_source = 'SYNTHETIC_TEST'
        OR (
            usage_source = 'PROVIDER_REPORTED'
            AND input_tokens IS NOT NULL
            AND output_tokens IS NOT NULL
            AND total_tokens IS NOT NULL
        )
    ),
    ADD CONSTRAINT ck_model_call_cost_status CHECK (
        cost_status IN ('UNPRICED','USAGE_UNAVAILABLE','ESTIMATED','TEST_ONLY')
    ),
    ADD CONSTRAINT ck_model_call_reserved_cost
        CHECK (reserved_cost_micros IS NULL OR reserved_cost_micros >= 0),
    ADD CONSTRAINT ck_model_call_cost_state CHECK (
        (
            cost_status = 'ESTIMATED'
            AND price_version IS NOT NULL
            AND price_currency ~ '^[A-Z]{3}$'
            AND estimated_cost_micros IS NOT NULL
            AND estimated_cost_micros >= 0
        )
        OR (
            cost_status <> 'ESTIMATED'
            AND estimated_cost_micros IS NULL
        )
    );

CREATE INDEX idx_model_call_project_usage
    ON ai_model_call_log(
        hospital_id, project_id, started_at DESC, logical_model_type
    );

CREATE TABLE project_model_budget (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL,
    project_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    max_call_cost_micros BIGINT NOT NULL,
    max_project_cost_micros BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_project_model_budget_scope
        UNIQUE (hospital_id, project_id),
    CONSTRAINT uk_project_model_budget_tenant
        UNIQUE (hospital_id, id),
    CONSTRAINT fk_project_model_budget_project
        FOREIGN KEY (hospital_id, project_id)
        REFERENCES research_project(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_project_model_budget_creator
        FOREIGN KEY (hospital_id, created_by)
        REFERENCES platform_user(hospital_id, id),
    CONSTRAINT ck_project_model_budget_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_project_model_budget_values
        CHECK (
            max_call_cost_micros > 0
            AND max_project_cost_micros >= max_call_cost_micros
        ),
    CONSTRAINT ck_project_model_budget_status
        CHECK (status IN ('ACTIVE','DISABLED'))
);

CREATE TABLE protocol_section_model_candidate (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL,
    project_id UUID NOT NULL,
    agent_task_id UUID NOT NULL,
    protocol_id UUID NOT NULL,
    section_id UUID NOT NULL,
    section_code VARCHAR(80) NOT NULL,
    base_version_no INTEGER NOT NULL,
    model_call_id UUID,
    prompt_version VARCHAR(80) NOT NULL,
    content TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    used_evidence_keys_json JSONB NOT NULL,
    allowed_evidence_sha256 CHAR(64) NOT NULL,
    issues_to_confirm_json JSONB NOT NULL,
    validation_json JSONB NOT NULL,
    status VARCHAR(30) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    applied_by UUID,
    applied_at TIMESTAMPTZ,
    applied_version_no INTEGER,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_protocol_model_candidate_call
        UNIQUE (hospital_id, model_call_id),
    CONSTRAINT uk_protocol_model_candidate_tenant
        UNIQUE (hospital_id, id),
    CONSTRAINT fk_protocol_model_candidate_project
        FOREIGN KEY (hospital_id, project_id)
        REFERENCES research_project(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_protocol_model_candidate_task
        FOREIGN KEY (hospital_id, agent_task_id)
        REFERENCES ai_agent_task(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_protocol_model_candidate_protocol
        FOREIGN KEY (hospital_id, protocol_id)
        REFERENCES research_protocol(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_protocol_model_candidate_section
        FOREIGN KEY (hospital_id, section_id)
        REFERENCES research_protocol_section(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_protocol_model_candidate_call_log
        FOREIGN KEY (hospital_id, model_call_id)
        REFERENCES ai_model_call_log(hospital_id, id)
        ON DELETE SET NULL (model_call_id),
    CONSTRAINT fk_protocol_model_candidate_applier
        FOREIGN KEY (hospital_id, applied_by)
        REFERENCES platform_user(hospital_id, id),
    CONSTRAINT ck_protocol_model_candidate_base_version
        CHECK (base_version_no > 0),
    CONSTRAINT ck_protocol_model_candidate_hash
        CHECK (
            content_sha256 ~ '^[0-9a-f]{64}$'
            AND allowed_evidence_sha256 ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT ck_protocol_model_candidate_status
        CHECK (status IN ('VALIDATED','REJECTED','APPLIED','SUPERSEDED')),
    CONSTRAINT ck_protocol_model_candidate_state
        CHECK (
            (
                status = 'APPLIED'
                AND applied_by IS NOT NULL
                AND applied_at IS NOT NULL
                AND applied_version_no IS NOT NULL
                AND applied_version_no > base_version_no
            )
            OR (
                status <> 'APPLIED'
                AND applied_by IS NULL
                AND applied_at IS NULL
                AND applied_version_no IS NULL
            )
        )
);

CREATE INDEX idx_protocol_model_candidate_section
    ON protocol_section_model_candidate(
        hospital_id, project_id, section_id, generated_at DESC
    );

CREATE TABLE protocol_section_model_review (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL,
    project_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    model_call_id UUID,
    candidate_content_sha256 CHAR(64) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    issues_json JSONB NOT NULL,
    summary VARCHAR(2000) NOT NULL,
    advisory_only BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_protocol_model_review_candidate
        UNIQUE (hospital_id, candidate_id),
    CONSTRAINT uk_protocol_model_review_call
        UNIQUE (hospital_id, model_call_id),
    CONSTRAINT uk_protocol_model_review_tenant
        UNIQUE (hospital_id, id),
    CONSTRAINT fk_protocol_model_review_project
        FOREIGN KEY (hospital_id, project_id)
        REFERENCES research_project(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_protocol_model_review_candidate
        FOREIGN KEY (hospital_id, candidate_id)
        REFERENCES protocol_section_model_candidate(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_protocol_model_review_call
        FOREIGN KEY (hospital_id, model_call_id)
        REFERENCES ai_model_call_log(hospital_id, id)
        ON DELETE SET NULL (model_call_id),
    CONSTRAINT ck_protocol_model_review_hash
        CHECK (candidate_content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_protocol_model_review_severity
        CHECK (severity IN ('NONE','LOW','MEDIUM','HIGH','BLOCKING')),
    CONSTRAINT ck_protocol_model_review_advisory
        CHECK (advisory_only = TRUE)
);

CREATE TABLE observational_design_model_advice (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL,
    project_id UUID NOT NULL,
    agent_task_id UUID NOT NULL,
    model_call_id UUID,
    rule_version VARCHAR(80) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    rule_recommended_study_type VARCHAR(40) NOT NULL,
    model_selected_study_type VARCHAR(40) NOT NULL,
    advice_json JSONB NOT NULL,
    advice_sha256 CHAR(64) NOT NULL,
    conflicts_json JSONB NOT NULL,
    conflict_count INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    advisory_only BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_design_model_advice_call
        UNIQUE (hospital_id, model_call_id),
    CONSTRAINT uk_design_model_advice_tenant
        UNIQUE (hospital_id, id),
    CONSTRAINT fk_design_model_advice_project
        FOREIGN KEY (hospital_id, project_id)
        REFERENCES research_project(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_design_model_advice_task
        FOREIGN KEY (hospital_id, agent_task_id)
        REFERENCES ai_agent_task(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_design_model_advice_call
        FOREIGN KEY (hospital_id, model_call_id)
        REFERENCES ai_model_call_log(hospital_id, id)
        ON DELETE SET NULL (model_call_id),
    CONSTRAINT ck_design_model_advice_types CHECK (
        rule_recommended_study_type IN (
            'CROSS_SECTIONAL','COHORT','CASE_CONTROL'
        )
        AND model_selected_study_type IN (
            'CROSS_SECTIONAL','COHORT','CASE_CONTROL'
        )
    ),
    CONSTRAINT ck_design_model_advice_hash
        CHECK (advice_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_design_model_advice_status
        CHECK (status IN ('ALIGNED','CONFLICT')),
    CONSTRAINT ck_design_model_advice_conflicts CHECK (
        conflict_count >= 0
        AND (
            (status = 'ALIGNED' AND conflict_count = 0)
            OR (status = 'CONFLICT' AND conflict_count > 0)
        )
    ),
    CONSTRAINT ck_design_model_advice_advisory
        CHECK (advisory_only = TRUE)
);

CREATE INDEX idx_design_model_advice_project
    ON observational_design_model_advice(
        hospital_id, project_id, created_at DESC
    );

CREATE TABLE model_evaluation_run (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL,
    started_by UUID NOT NULL,
    dataset_version VARCHAR(80) NOT NULL,
    data_classification VARCHAR(40) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    route_policy_version VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    case_count INTEGER NOT NULL,
    passed_count INTEGER,
    report_sha256 CHAR(64),
    report_json JSONB,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_model_evaluation_run_tenant
        UNIQUE (hospital_id, id),
    CONSTRAINT uk_model_evaluation_run_idempotency
        UNIQUE (hospital_id, started_by, idempotency_key),
    CONSTRAINT fk_model_evaluation_run_hospital
        FOREIGN KEY (hospital_id) REFERENCES hospital(id),
    CONSTRAINT fk_model_evaluation_run_starter
        FOREIGN KEY (hospital_id, started_by)
        REFERENCES platform_user(hospital_id, id),
    CONSTRAINT ck_model_evaluation_classification
        CHECK (data_classification = 'SYNTHETIC_ANONYMOUS'),
    CONSTRAINT ck_model_evaluation_run_request_hash
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_model_evaluation_status
        CHECK (
            status IN (
                'RUNNING','WAITING_EXPERT_SCORING','COMPLETED','FAILED'
            )
        ),
    CONSTRAINT ck_model_evaluation_counts
        CHECK (
            case_count > 0
            AND (passed_count IS NULL OR passed_count BETWEEN 0 AND case_count)
        ),
    CONSTRAINT ck_model_evaluation_report
        CHECK (
            (
                status = 'RUNNING'
                AND passed_count IS NULL
                AND report_sha256 IS NULL
                AND report_json IS NULL
                AND completed_at IS NULL
            )
            OR (
                status <> 'RUNNING'
                AND passed_count IS NOT NULL
                AND report_sha256 ~ '^[0-9a-f]{64}$'
                AND report_json IS NOT NULL
                AND completed_at IS NOT NULL
            )
        )
);

CREATE INDEX idx_model_evaluation_run_hospital
    ON model_evaluation_run(hospital_id, started_at DESC);

CREATE TABLE model_evaluation_case_result (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL,
    evaluation_run_id UUID NOT NULL,
    case_key VARCHAR(80) NOT NULL,
    logical_model_type VARCHAR(40) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    output_sha256 CHAR(64),
    passed BOOLEAN NOT NULL,
    metrics_json JSONB NOT NULL,
    error_code VARCHAR(80),
    evaluated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_model_evaluation_case
        UNIQUE (evaluation_run_id, case_key, logical_model_type),
    CONSTRAINT uk_model_evaluation_case_tenant
        UNIQUE (hospital_id, id),
    CONSTRAINT fk_model_evaluation_case_run
        FOREIGN KEY (hospital_id, evaluation_run_id)
        REFERENCES model_evaluation_run(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_model_evaluation_case_type
        CHECK (
            logical_model_type IN (
                'RESEARCH_FAST','RESEARCH_STANDARD',
                'RESEARCH_REASONING','RESEARCH_REVIEW'
            )
        ),
    CONSTRAINT ck_model_evaluation_case_hash
        CHECK (output_sha256 IS NULL OR output_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_model_evaluation_case_error
        CHECK (
            (passed = TRUE AND error_code IS NULL)
            OR passed = FALSE
        )
);

CREATE TABLE model_evaluation_expert_score (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL,
    evaluation_run_id UUID NOT NULL,
    responsibility VARCHAR(40) NOT NULL,
    reviewer_id UUID NOT NULL,
    correctness_score SMALLINT NOT NULL,
    completeness_score SMALLINT NOT NULL,
    safety_score SMALLINT NOT NULL,
    actionability_score SMALLINT NOT NULL,
    recommendation VARCHAR(40) NOT NULL,
    comment VARCHAR(2000) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_model_evaluation_expert_responsibility
        UNIQUE (evaluation_run_id, responsibility),
    CONSTRAINT uk_model_evaluation_expert_reviewer
        UNIQUE (evaluation_run_id, reviewer_id),
    CONSTRAINT uk_model_evaluation_expert_idempotency
        UNIQUE (hospital_id, reviewer_id, idempotency_key),
    CONSTRAINT uk_model_evaluation_expert_score_tenant
        UNIQUE (hospital_id, id),
    CONSTRAINT fk_model_evaluation_expert_run
        FOREIGN KEY (hospital_id, evaluation_run_id)
        REFERENCES model_evaluation_run(hospital_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_model_evaluation_expert_reviewer
        FOREIGN KEY (hospital_id, reviewer_id)
        REFERENCES platform_user(hospital_id, id),
    CONSTRAINT ck_model_evaluation_expert_responsibility
        CHECK (responsibility IN ('MEDICAL_REVIEW','STATISTICAL_REVIEW')),
    CONSTRAINT ck_model_evaluation_expert_scores
        CHECK (
            correctness_score BETWEEN 1 AND 5
            AND completeness_score BETWEEN 1 AND 5
            AND safety_score BETWEEN 1 AND 5
            AND actionability_score BETWEEN 1 AND 5
        ),
    CONSTRAINT ck_model_evaluation_expert_recommendation
        CHECK (recommendation IN ('ACCEPT','REVISE','REJECT')),
    CONSTRAINT ck_model_evaluation_expert_request_hash
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION reject_same_model_evaluation_reviewer()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM model_evaluation_expert_score existing
        WHERE existing.hospital_id = NEW.hospital_id
          AND existing.evaluation_run_id = NEW.evaluation_run_id
          AND existing.responsibility <> NEW.responsibility
          AND existing.reviewer_id = NEW.reviewer_id
    ) THEN
        RAISE EXCEPTION
            'medical and statistical evaluation reviewers must be different';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_model_evaluation_reviewer_independence
BEFORE INSERT ON model_evaluation_expert_score
FOR EACH ROW EXECUTE FUNCTION reject_same_model_evaluation_reviewer();

CREATE FUNCTION reject_model_evaluation_score_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION
        'model_evaluation_expert_score is append-only; % is forbidden',
        TG_OP;
END
$$;

CREATE TRIGGER trg_model_evaluation_score_immutable
BEFORE UPDATE OR DELETE ON model_evaluation_expert_score
FOR EACH ROW EXECUTE FUNCTION reject_model_evaluation_score_mutation();
