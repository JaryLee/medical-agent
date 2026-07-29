CREATE TABLE claim_citation_validation_task (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    project_id UUID NOT NULL REFERENCES research_project(id) ON DELETE CASCADE,
    agent_task_id UUID NOT NULL REFERENCES ai_agent_task(id),
    protocol_id UUID NOT NULL REFERENCES research_protocol(id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL,
    claim_count INTEGER NOT NULL,
    citation_link_count INTEGER NOT NULL,
    abstract_only_claim_count INTEGER NOT NULL,
    needs_expert_review_claim_count INTEGER NOT NULL,
    input_sha256 CHAR(64) NOT NULL,
    validator_version VARCHAR(100) NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_claim_citation_validation_agent UNIQUE (agent_task_id),
    CONSTRAINT uk_claim_citation_validation_protocol UNIQUE (protocol_id),
    CONSTRAINT ck_claim_citation_validation_status
        CHECK (status IN ('COMPLETED','WAITING_EXPERT_REVIEW')),
    CONSTRAINT ck_claim_citation_validation_counts CHECK (
        claim_count > 0
        AND citation_link_count >= 0
        AND abstract_only_claim_count >= 0
        AND needs_expert_review_claim_count >= 0
        AND abstract_only_claim_count + needs_expert_review_claim_count = claim_count
    )
);

CREATE INDEX idx_claim_citation_validation_project
    ON claim_citation_validation_task(hospital_id,project_id,created_at DESC);

CREATE TABLE research_claim (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    validation_task_id UUID NOT NULL
        REFERENCES claim_citation_validation_task(id) ON DELETE CASCADE,
    protocol_id UUID NOT NULL REFERENCES research_protocol(id) ON DELETE CASCADE,
    section_id UUID NOT NULL REFERENCES research_protocol_section(id) ON DELETE CASCADE,
    section_code VARCHAR(80) NOT NULL,
    claim_order INTEGER NOT NULL,
    claim_type VARCHAR(50) NOT NULL,
    claim_text TEXT NOT NULL,
    support_status VARCHAR(40) NOT NULL,
    expert_confirmation_status VARCHAR(40) NOT NULL,
    linked_citation_count INTEGER NOT NULL,
    issues_to_confirm_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_research_claim_order
        UNIQUE (validation_task_id,section_id,claim_order),
    CONSTRAINT ck_research_claim_order CHECK (claim_order > 0),
    CONSTRAINT ck_research_claim_support CHECK (
        support_status IN (
            'SUPPORTED','PARTIALLY_SUPPORTED','NOT_SUPPORTED',
            'ABSTRACT_ONLY','NEEDS_EXPERT_REVIEW'
        )
    ),
    CONSTRAINT ck_research_claim_confirmation CHECK (
        expert_confirmation_status IN ('PENDING_REVIEW','CONFIRMED','REJECTED')
    ),
    CONSTRAINT ck_research_claim_link_count CHECK (linked_citation_count >= 0)
);

CREATE INDEX idx_research_claim_section
    ON research_claim(hospital_id,protocol_id,section_id,claim_order);

CREATE TABLE claim_citation_link (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    research_claim_id UUID NOT NULL REFERENCES research_claim(id) ON DELETE CASCADE,
    link_order INTEGER NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    pmid VARCHAR(20),
    doi VARCHAR(255),
    title TEXT NOT NULL,
    support_level VARCHAR(40) NOT NULL,
    evidence_scope VARCHAR(40) NOT NULL,
    evidence_excerpt TEXT NOT NULL,
    excerpt_location VARCHAR(80) NOT NULL,
    excerpt_sha256 CHAR(64) NOT NULL,
    citation_validation_status VARCHAR(60) NOT NULL,
    manual_confirmation_status VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_claim_citation_link_order
        UNIQUE (research_claim_id,link_order),
    CONSTRAINT uk_claim_citation_link_source
        UNIQUE (research_claim_id,source_type,pmid),
    CONSTRAINT ck_claim_citation_link_order CHECK (link_order > 0),
    CONSTRAINT ck_claim_citation_link_source CHECK (
        source_type IN ('PUBMED','PMC_FULL_TEXT','CLINICAL_TRIALS_GOV')
    ),
    CONSTRAINT ck_claim_citation_link_support CHECK (
        support_level IN (
            'SUPPORTED','PARTIALLY_SUPPORTED','NOT_SUPPORTED',
            'ABSTRACT_ONLY','NEEDS_EXPERT_REVIEW'
        )
    ),
    CONSTRAINT ck_claim_citation_link_scope CHECK (
        evidence_scope IN (
            'ABSTRACT_ONLY','FULL_TEXT','REGISTRY_METADATA_ONLY',
            'REGISTRY_RESULTS_AVAILABLE','TITLE_ONLY'
        )
    ),
    CONSTRAINT ck_claim_citation_link_confirmation CHECK (
        manual_confirmation_status IN ('PENDING_REVIEW','CONFIRMED','REJECTED')
    ),
    CONSTRAINT ck_claim_citation_link_identifier CHECK (
        pmid IS NOT NULL OR doi IS NOT NULL
    )
);

CREATE INDEX idx_claim_citation_link_claim
    ON claim_citation_link(hospital_id,research_claim_id,link_order);
