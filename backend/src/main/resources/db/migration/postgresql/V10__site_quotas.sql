-- Storage limits administrators set on top-level sites. A site with no row has no limit.
CREATE TABLE site_quota (
    site_id     UUID PRIMARY KEY REFERENCES node (id) ON DELETE CASCADE,
    max_bytes   BIGINT NOT NULL,
    warn_bytes  BIGINT,
    warned_at   TIMESTAMP WITH TIME ZONE,
    updated_by  UUID,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
