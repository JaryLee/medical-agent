CREATE TABLE citation_style_version (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    style_code VARCHAR(80) NOT NULL,
    style_name VARCHAR(200) NOT NULL,
    version_no INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    layout VARCHAR(30) NOT NULL,
    author_limit INTEGER NOT NULL,
    et_al_text VARCHAR(30) NOT NULL,
    include_pmid BOOLEAN NOT NULL,
    include_doi BOOLEAN NOT NULL,
    include_evidence_scope BOOLEAN NOT NULL,
    evidence_scope_label VARCHAR(80) NOT NULL,
    created_by UUID NOT NULL REFERENCES platform_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    published_by UUID REFERENCES platform_user(id),
    published_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_citation_style_version
        UNIQUE (hospital_id, style_code, version_no),
    CONSTRAINT ck_citation_style_status
        CHECK (status IN ('VALIDATED','PUBLISHED','ARCHIVED')),
    CONSTRAINT ck_citation_style_layout
        CHECK (layout IN ('VANCOUVER','GB_T_7714')),
    CONSTRAINT ck_citation_style_author_limit
        CHECK (author_limit BETWEEN 1 AND 20),
    CONSTRAINT ck_citation_style_pmid
        CHECK (include_pmid),
    CONSTRAINT ck_citation_style_publish_state CHECK (
        (status = 'PUBLISHED' AND published_by IS NOT NULL AND published_at IS NOT NULL)
        OR status <> 'PUBLISHED'
    )
);

CREATE UNIQUE INDEX uk_citation_style_published
    ON citation_style_version(hospital_id, style_code)
    WHERE status = 'PUBLISHED';

CREATE INDEX idx_citation_style_history
    ON citation_style_version(hospital_id, style_code, version_no DESC);

ALTER TABLE document_export_record
    ADD COLUMN citation_style_version_id UUID
        REFERENCES citation_style_version(id);

CREATE INDEX idx_document_export_citation_style
    ON document_export_record(hospital_id, citation_style_version_id);
