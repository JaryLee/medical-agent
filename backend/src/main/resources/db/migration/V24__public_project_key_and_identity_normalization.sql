ALTER TABLE research_project
    ADD COLUMN project_key VARCHAR(30);

CREATE FUNCTION generate_public_project_key()
RETURNS VARCHAR(30)
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    random_bytes BYTEA := decode(
        replace(gen_random_uuid()::text, '-', '')
        || replace(gen_random_uuid()::text, '-', ''),
        'hex'
    );
    alphabet CONSTANT TEXT := '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
    generated TEXT := 'prj_';
    position INTEGER;
BEGIN
    FOR position IN 0..25 LOOP
        generated := generated
            || substr(alphabet, (get_byte(random_bytes, position) % 32) + 1, 1);
    END LOOP;
    RETURN generated;
END
$$;

UPDATE research_project
SET project_key = generate_public_project_key()
WHERE project_key IS NULL;

ALTER TABLE research_project
    ALTER COLUMN project_key SET NOT NULL,
    ADD CONSTRAINT uk_research_project_project_key UNIQUE (project_key),
    ADD CONSTRAINT ck_research_project_project_key
        CHECK (project_key ~ '^prj_[0-9A-HJKMNP-TV-Z]{26}$');

CREATE FUNCTION prevent_public_project_key_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.project_key IS DISTINCT FROM OLD.project_key THEN
        RAISE EXCEPTION 'research_project.project_key is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_research_project_key_immutable
BEFORE UPDATE OF project_key ON research_project
FOR EACH ROW
EXECUTE FUNCTION prevent_public_project_key_change();

DROP FUNCTION generate_public_project_key();

ALTER TABLE hospital
    ADD CONSTRAINT ck_hospital_code_canonical
        CHECK (
            code = upper(btrim(code))
            AND code ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'
        );

ALTER TABLE platform_user
    ADD COLUMN username_normalized VARCHAR(100)
        GENERATED ALWAYS AS (
            lower(normalize(btrim(username), NFKC))
        ) STORED,
    ADD CONSTRAINT ck_platform_user_username_canonical
        CHECK (
            username = btrim(username)
            AND char_length(username) BETWEEN 1 AND 100
            AND username !~ '[[:cntrl:]]'
        );

CREATE UNIQUE INDEX uk_user_hospital_username_normalized
    ON platform_user(hospital_id, username_normalized)
    WHERE hospital_id IS NOT NULL;

CREATE UNIQUE INDEX uk_platform_username_normalized
    ON platform_user(username_normalized)
    WHERE hospital_id IS NULL;
