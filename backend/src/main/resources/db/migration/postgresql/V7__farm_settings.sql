-- Settings administrators change at run time, as opposed to install-time configuration.
CREATE TABLE farm_setting (
    name        VARCHAR(120) PRIMARY KEY,
    setting_value  VARCHAR(4000) NOT NULL,
    updated_by  UUID REFERENCES principal (id),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
