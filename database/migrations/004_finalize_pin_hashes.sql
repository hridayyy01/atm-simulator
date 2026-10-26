-- Run only after atm.tools.MigratePins reports success, with the application stopped.
USE bankdb;
SET SESSION sql_mode = CONCAT_WS(',', NULLIF(@@SESSION.sql_mode, ''), 'STRICT_ALL_TABLES');

-- Enforce completeness before removing plaintext. If any hash is NULL, this
-- fails before the DROP statements. Do not use the mysql --force option.
ALTER TABLE login MODIFY COLUMN pin_hash VARCHAR(255) NOT NULL;
ALTER TABLE login DROP COLUMN pin;
ALTER TABLE bank DROP COLUMN pin;
