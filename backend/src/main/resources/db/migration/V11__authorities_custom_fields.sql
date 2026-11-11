-- Authority control: preferred headings for authors and subjects, with variant (see-from) forms.
CREATE TABLE authority (
    id         BIGSERIAL PRIMARY KEY,
    type       VARCHAR(10)  NOT NULL,          -- AUTHOR | SUBJECT
    heading    VARCHAR(300) NOT NULL,
    variants   VARCHAR(1000),                  -- '|' separated other forms that should lead here
    note       VARCHAR(500),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_authority_heading ON authority (type, lower(heading));

-- Custom catalog fields defined by the library; values are stored per record as JSON text.
CREATE TABLE custom_field (
    id          BIGSERIAL PRIMARY KEY,
    field_key   VARCHAR(40)  NOT NULL UNIQUE,
    label       VARCHAR(80)  NOT NULL,
    field_type  VARCHAR(10)  NOT NULL,         -- TEXT | NUMBER | DATE | SELECT
    options     VARCHAR(1000),                 -- '|' separated choices for SELECT
    show_in_opac BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order  INT          NOT NULL DEFAULT 0
);
ALTER TABLE bib_record ADD COLUMN custom_fields TEXT;
