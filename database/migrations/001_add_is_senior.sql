-- Run once on an existing bankdb installation that lacks signuptwo.isSenior.
-- Existing rows are preserved; NULL means senior status was not recorded.
USE bankdb;

ALTER TABLE signuptwo
    ADD COLUMN isSenior VARCHAR(3) NULL DEFAULT NULL;
