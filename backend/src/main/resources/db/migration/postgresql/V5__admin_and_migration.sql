-- Farm administration: background jobs (backups, restores, imports, migrations).
CREATE TABLE admin_job (
    id           UUID PRIMARY KEY,
    type         VARCHAR(32)   NOT NULL,
    title        VARCHAR(255)  NOT NULL,
    status       VARCHAR(16)   NOT NULL,          -- QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELLED
    progress     INTEGER       NOT NULL,          -- 0..100
    message      VARCHAR(1000),
    params       JSONB,
    result       JSONB,
    log          VARCHAR(200000),
    created_by   UUID NOT NULL REFERENCES principal (id),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at   TIMESTAMP WITH TIME ZONE,
    finished_at  TIMESTAMP WITH TIME ZONE
);
CREATE INDEX ix_admin_job_created ON admin_job (created_at);

-- SharePoint migration: farms to copy from, site-to-site tasks, and the source-to-target map
-- that drives delta sync and old-URL redirects.
CREATE TABLE migration_source (
    id          UUID PRIMARY KEY,
    name        VARCHAR(255)  NOT NULL,
    base_url    VARCHAR(1000) NOT NULL,             -- https://sharepoint.contoso.com
    auth        VARCHAR(16)   NOT NULL,             -- NTLM | BASIC | BEARER | COOKIE | NONE
    username    VARCHAR(255),
    domain      VARCHAR(255),
    secret      VARCHAR(8000),                      -- encrypted password, token or cookie
    created_by  UUID NOT NULL REFERENCES principal (id),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE migration_task (
    id               UUID PRIMARY KEY,
    source_id        UUID NOT NULL REFERENCES migration_source (id) ON DELETE CASCADE,
    source_web       VARCHAR(2000) NOT NULL,        -- server-relative URL of the source site, e.g. /sites/hr
    target_site      VARCHAR(63)   NOT NULL,        -- NEXORA site URL name
    options          JSONB,
    scan             JSONB,
    status           VARCHAR(16)   NOT NULL,        -- NEW | SCANNED | COPIED | SYNCED | FAILED
    last_job_id      UUID,
    last_sync_at     TIMESTAMP WITH TIME ZONE,      -- start of the last successful copy (delta watermark)
    created_by       UUID NOT NULL REFERENCES principal (id),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE migration_item (
    id               UUID PRIMARY KEY,
    task_id          UUID NOT NULL REFERENCES migration_task (id) ON DELETE CASCADE,
    kind             VARCHAR(16)   NOT NULL,
    source_key       VARCHAR(255)  NOT NULL,        -- SharePoint UniqueId, or list GUID + item id
    source_url       VARCHAR(4000) NOT NULL,        -- server-relative URL in the source farm
    node_id          UUID          NOT NULL,
    source_modified  TIMESTAMP WITH TIME ZONE,
    source_version   VARCHAR(32)
);
CREATE UNIQUE INDEX ux_migration_item_key ON migration_item (task_id, source_key);
CREATE INDEX ix_migration_item_url ON migration_item (lower(source_url));
CREATE INDEX ix_migration_item_source_key ON migration_item (source_key);
