-- Notes become rich documents: add an optional title and widen the body.
-- content moves TEXT -> MEDIUMTEXT because utf8mb4 documents (notably Japanese)
-- can exceed TEXT's ~64KB byte limit well before a sane character count.
ALTER TABLE note
    ADD COLUMN title VARCHAR(255) NULL AFTER content;

ALTER TABLE note
    MODIFY COLUMN content MEDIUMTEXT NOT NULL;
