-- NEXORA core schema: identity, content tree, versions, ACL, columns, audit.

CREATE TABLE principal (
    id             UUID PRIMARY KEY,
    kind           VARCHAR(16)  NOT NULL,              -- USER | GROUP
    login          VARCHAR(255) NOT NULL,              -- user login or group name
    display_name   VARCHAR(255) NOT NULL,
    email          VARCHAR(320),
    password_hash  VARCHAR(255),                       -- null for directory-backed users
    is_admin       BOOLEAN      NOT NULL DEFAULT FALSE,
    is_enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    external_id    VARCHAR(255),                       -- AD SID / OIDC subject, for migration mapping
    site_id        UUID,                               -- set for site-scoped groups (Owners/Members/Visitors)
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX ux_principal_kind_login ON principal (kind, lower(login));

CREATE TABLE group_member (
    group_id   UUID NOT NULL REFERENCES principal (id) ON DELETE CASCADE,
    member_id  UUID NOT NULL REFERENCES principal (id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, member_id)
);
CREATE INDEX ix_group_member_member ON group_member (member_id);

CREATE TABLE node (
    id                    UUID PRIMARY KEY,
    kind                  VARCHAR(16)   NOT NULL,      -- SITE | LIBRARY | FOLDER | FILE
    parent_id             UUID REFERENCES node (id),
    site_id               UUID,
    name                  VARCHAR(255)  NOT NULL,
    path                  VARCHAR(4000) NOT NULL,      -- SharePoint-style server-relative URL
    title                 VARCHAR(255),
    description           TEXT,
    inherits_permissions  BOOLEAN       NOT NULL DEFAULT TRUE,
    metadata              JSONB,                       -- column values (files/folders)
    settings              JSONB,                       -- library settings (versioning etc.)
    current_version_id    UUID,
    size_bytes            BIGINT,
    mime_type             VARCHAR(255),
    checked_out_by        UUID REFERENCES principal (id),
    checked_out_at        TIMESTAMP WITH TIME ZONE,
    working_storage_key   VARCHAR(512),                -- private draft while checked out
    working_size_bytes    BIGINT,
    working_sha256        VARCHAR(64),
    working_mime_type     VARCHAR(255),
    created_by            UUID NOT NULL REFERENCES principal (id),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    modified_by           UUID NOT NULL REFERENCES principal (id),
    modified_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at            TIMESTAMP WITH TIME ZONE,
    deleted_by            UUID REFERENCES principal (id),
    deleted_root_id       UUID                         -- recycle-bin batch: the node the user deleted
);
CREATE INDEX ix_node_parent ON node (parent_id);
CREATE INDEX ix_node_site ON node (site_id);
CREATE INDEX ix_node_path ON node (path);
CREATE INDEX ix_node_deleted_root ON node (deleted_root_id);
CREATE UNIQUE INDEX ux_node_live_path ON node (lower(path)) WHERE deleted_at IS NULL;

CREATE TABLE file_version (
    id            UUID PRIMARY KEY,
    node_id       UUID NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    major         INT  NOT NULL,
    minor         INT  NOT NULL,
    storage_key   VARCHAR(512) NOT NULL,
    size_bytes    BIGINT NOT NULL,
    sha256        VARCHAR(64) NOT NULL,
    mime_type     VARCHAR(255),
    file_name     VARCHAR(255) NOT NULL,
    comment       TEXT,
    created_by    UUID NOT NULL REFERENCES principal (id),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (node_id, major, minor)
);
CREATE INDEX ix_file_version_storage_key ON file_version (storage_key);

CREATE TABLE acl_entry (
    id            UUID PRIMARY KEY,
    node_id       UUID NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    principal_id  UUID NOT NULL REFERENCES principal (id) ON DELETE CASCADE,
    level         VARCHAR(32) NOT NULL,                -- READ | CONTRIBUTE | EDIT | FULL_CONTROL
    UNIQUE (node_id, principal_id)
);

CREATE TABLE column_def (
    id            UUID PRIMARY KEY,
    library_id    UUID NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    internal_name VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(255) NOT NULL,
    type          VARCHAR(16)  NOT NULL,               -- TEXT | NOTE | NUMBER | DATE | BOOLEAN | CHOICE
    required      BOOLEAN      NOT NULL DEFAULT FALSE,
    choices       JSONB,
    position      INT          NOT NULL DEFAULT 0,
    UNIQUE (library_id, internal_name)
);

CREATE TABLE audit_event (
    id            UUID PRIMARY KEY,
    occurred_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_id      UUID,
    actor_login   VARCHAR(255),
    action        VARCHAR(64) NOT NULL,
    node_id       UUID,
    site_id       UUID,
    node_path     VARCHAR(4000),
    details       JSONB
);
CREATE INDEX ix_audit_occurred ON audit_event (occurred_at DESC);
CREATE INDEX ix_audit_node ON audit_event (node_id);
CREATE INDEX ix_audit_site ON audit_event (site_id);
