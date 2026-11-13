-- Every committed import (catalog file or patron list), for the data migration validation report.
CREATE TABLE import_run (
    id             BIGSERIAL PRIMARY KEY,
    kind           VARCHAR(20)  NOT NULL,   -- CATALOG or PATRONS
    filename       VARCHAR(255),
    rows_in_file   INT          NOT NULL DEFAULT 0,
    created        INT          NOT NULL DEFAULT 0,
    matched        INT          NOT NULL DEFAULT 0,   -- titles matched to existing records / patrons updated
    copies_created INT          NOT NULL DEFAULT 0,
    copies_skipped INT          NOT NULL DEFAULT 0,
    problems       TEXT,
    run_by         VARCHAR(60)  NOT NULL,
    run_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
