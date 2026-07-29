ALTER TABLE project_file
    ADD COLUMN scan_engine VARCHAR(80) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN extracted_characters INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN extraction_status VARCHAR(40) NOT NULL DEFAULT 'NOT_EXTRACTED';

ALTER TABLE project_file
    ADD CONSTRAINT chk_project_file_extracted_characters
        CHECK (extracted_characters >= 0);
