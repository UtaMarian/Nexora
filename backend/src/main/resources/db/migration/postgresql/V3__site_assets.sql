-- Small site branding assets (logo), kept apart from the content-addressed document blobs.
CREATE TABLE site_asset (
    id          UUID PRIMARY KEY,
    site_id     UUID NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    kind        VARCHAR(32)  NOT NULL,
    mime_type   VARCHAR(100) NOT NULL,
    sha256      VARCHAR(64)  NOT NULL,
    data        BYTEA        NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (site_id, kind)
);
