-- The library's sections / areas. Copies and stocktakes pick from this list, so the same section is
-- always spelled the same way (a stocktake compares names to find misplaced copies).
CREATE TABLE library_section (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_section_name_ci ON library_section (lower(name));

-- Start with the usual sections plus every section already used on a copy.
INSERT INTO library_section (name, sort_order)
SELECT name, row_number() OVER (ORDER BY ord, name)
FROM (
    SELECT DISTINCT ON (lower(name)) name, ord FROM (
        SELECT * FROM (VALUES ('General Circulation', 1), ('Fiction Section', 2), ('Filipiniana', 3),
                              ('Reference Section', 4), ('Periodicals Section', 5), ('Thesis & Research Section', 6),
                              ('Audio-Visual Room', 7), ('Special Collections', 8)) AS d(name, ord)
        UNION ALL
        SELECT trim(location), 100 FROM item WHERE location IS NOT NULL AND trim(location) <> ''
    ) AS all_names
    ORDER BY lower(name), ord
) AS distinct_names;

-- Copies use the list's spelling.
UPDATE item i SET location = s.name FROM library_section s
WHERE i.location IS NOT NULL AND lower(trim(i.location)) = lower(s.name) AND i.location <> s.name;
UPDATE item SET location = NULL WHERE location IS NOT NULL AND trim(location) = '';
