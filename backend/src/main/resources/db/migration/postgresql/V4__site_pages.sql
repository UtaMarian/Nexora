-- Modern pages and news posts. The published canvas is what readers see; the draft holds unpublished edits.
CREATE TABLE site_page (
    id                 UUID PRIMARY KEY,
    site_id            UUID NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    name               VARCHAR(120)  NOT NULL,           -- URL name: /sites/<site>/SitePages/<name>.aspx
    kind               VARCHAR(16)   NOT NULL,           -- PAGE | NEWS
    title              VARCHAR(255)  NOT NULL,
    description        VARCHAR(1000),
    banner_image_id    UUID,                             -- image file node
    canvas             JSONB,                            -- published canvas (null until first publish)
    draft_title        VARCHAR(255),
    draft_description  VARCHAR(1000),
    draft_banner_id    UUID,
    draft_canvas       JSONB,                            -- unpublished edits (null when none)
    revision           INTEGER       NOT NULL,           -- optimistic concurrency for draft saves
    published_version  INTEGER       NOT NULL,           -- 0 = never published
    created_by         UUID NOT NULL REFERENCES principal (id),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    modified_by        UUID NOT NULL REFERENCES principal (id),
    modified_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    published_by       UUID REFERENCES principal (id),
    published_at       TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX ux_site_page_name ON site_page (site_id, name);
CREATE INDEX ix_site_page_site_kind ON site_page (site_id, kind, published_at);

CREATE TABLE site_page_version (
    id               UUID PRIMARY KEY,
    page_id          UUID NOT NULL REFERENCES site_page (id) ON DELETE CASCADE,
    version          INTEGER       NOT NULL,
    title            VARCHAR(255)  NOT NULL,
    description      VARCHAR(1000),
    banner_image_id  UUID,
    canvas           JSONB            NOT NULL,
    published_by     UUID NOT NULL REFERENCES principal (id),
    published_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (page_id, version)
);
