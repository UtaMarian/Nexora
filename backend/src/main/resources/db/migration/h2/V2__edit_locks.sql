-- Short-term edit locks taken by desktop Office over WebDAV (distinct from check-out).
CREATE TABLE edit_lock (
    id          UUID PRIMARY KEY,
    node_id     UUID NOT NULL UNIQUE REFERENCES node (id) ON DELETE CASCADE,
    token       VARCHAR(100) NOT NULL UNIQUE,
    owner_id    UUID NOT NULL REFERENCES principal (id) ON DELETE CASCADE,
    owner_info  TEXT,
    client      VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_edit_lock_expires ON edit_lock (expires_at);
