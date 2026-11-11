-- E-books come in two kinds:
--   * school-owned: the library uploads the PDF/EPUB and any signed-in patron may read it in the app;
--   * commercial: the file is never hosted here; the record only links to the vendor's platform.

ALTER TABLE bib_record
    ADD COLUMN external_url      VARCHAR(1000),
    ADD COLUMN external_provider VARCHAR(100);

CREATE TABLE digital_file (
    id                BIGSERIAL PRIMARY KEY,
    record_id         BIGINT       NOT NULL UNIQUE REFERENCES bib_record (id) ON DELETE CASCADE,
    storage_key       VARCHAR(80)  NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    format            VARCHAR(10)  NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    sha256            VARCHAR(64)  NOT NULL,
    uploaded_by       VARCHAR(60),
    uploaded_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Who opened which school-owned e-book (usage statistics).
CREATE TABLE ebook_view (
    id        BIGSERIAL PRIMARY KEY,
    record_id BIGINT      NOT NULL REFERENCES bib_record (id) ON DELETE CASCADE,
    patron_id BIGINT REFERENCES patron (id) ON DELETE SET NULL,
    viewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ebook_view_record ON ebook_view (record_id, viewed_at);
