-- Run once after 001 (if needed), before using atomic registration.
-- Existing row values are preserved. cardno PRIMARY KEY enforces card uniqueness.
USE bankdb;

-- Inspect and resolve any historical multiple-card registrations before upgrading.
-- The unique constraint below fails rather than removing duplicate records.
SELECT formno, COUNT(*) AS cards
FROM login
WHERE formno IS NOT NULL
GROUP BY formno
HAVING COUNT(*) > 1;

ALTER TABLE signup ENGINE=InnoDB;
ALTER TABLE signuptwo ENGINE=InnoDB;
ALTER TABLE login ENGINE=InnoDB,
    ADD CONSTRAINT uq_login_formno UNIQUE (formno);
