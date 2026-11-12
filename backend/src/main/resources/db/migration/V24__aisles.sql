-- Aisles of each section and how many shelves each has, set up by an administrator. A copy's aisle and
-- shelf are picked from these, and a stocktake can count one aisle against its shelf list.
CREATE TABLE library_aisle (
    id         BIGSERIAL PRIMARY KEY,
    section_id BIGINT      NOT NULL REFERENCES library_section (id) ON DELETE CASCADE,
    name       VARCHAR(20) NOT NULL,
    shelves    INT         NOT NULL CHECK (shelves BETWEEN 1 AND 50),
    sort_order INT         NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_aisle_name_ci ON library_aisle (section_id, lower(name));

-- Start from the aisles and shelves already recorded on copies.
INSERT INTO library_aisle (section_id, name, shelves, sort_order)
SELECT s.id, a.aisle,
       LEAST(50, GREATEST(1, a.max_shelf, a.shelf_count)),
       row_number() OVER (PARTITION BY s.id ORDER BY a.num NULLS LAST, a.aisle)
FROM (
    SELECT lower(trim(location)) AS loc, trim(aisle) AS aisle,
           max(CASE WHEN trim(shelf) ~ '^\d{1,2}$' THEN trim(shelf)::int END) AS max_shelf,
           count(DISTINCT lower(trim(shelf))) AS shelf_count,
           CASE WHEN trim(aisle) ~ '^\d{1,6}$' THEN trim(aisle)::int END AS num
    FROM item
    WHERE location IS NOT NULL AND aisle IS NOT NULL AND trim(aisle) <> ''
    GROUP BY 1, 2
) a
JOIN library_section s ON lower(s.name) = a.loc
ON CONFLICT DO NOTHING;
