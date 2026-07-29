CREATE TABLE platform_user (
    id UUID PRIMARY KEY,
    hospital_id UUID REFERENCES hospital(id),
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    force_password_change BOOLEAN NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_hospital_username UNIQUE (hospital_id, username)
);

CREATE TABLE role (
    code VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE user_role (
    user_id UUID NOT NULL REFERENCES platform_user(id),
    role_code VARCHAR(50) NOT NULL REFERENCES role(code),
    PRIMARY KEY (user_id, role_code)
);

CREATE TABLE user_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES platform_user(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE login_audit (
    id UUID PRIMARY KEY,
    hospital_id UUID,
    username VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    failure_code VARCHAR(80),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE operation_audit (
    id UUID PRIMARY KEY,
    hospital_id UUID,
    actor_user_id UUID,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(100),
    reason VARCHAR(300),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE idempotency_record (
    id UUID PRIMARY KEY,
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    user_id UUID NOT NULL REFERENCES platform_user(id),
    idempotency_key VARCHAR(100) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    resource_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_idempotency_scope UNIQUE (hospital_id, user_id, operation, idempotency_key)
);

INSERT INTO role(code, name) VALUES
    ('DOCTOR', '医生'),
    ('EXPERT', '专家'),
    ('HOSPITAL_ADMIN', '医院管理员'),
    ('PLATFORM_ADMIN', '平台管理员'),
    ('AUDIT_ADMIN', '审计管理员');
