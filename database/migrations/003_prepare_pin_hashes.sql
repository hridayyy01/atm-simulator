-- Stop all application instances. Apply once to the legacy schema after 002.
-- Keep legacy PINs only until the Java migration has hashed them.
USE bankdb;

ALTER TABLE login
    MODIFY COLUMN pin VARCHAR(10) NULL,
    ADD COLUMN pin_hash VARCHAR(255) NULL;

-- Preserve a card reference where a legacy transaction's PIN identifies one card.
-- Ambiguous and unmatched transaction rows are retained with a NULL reference.
ALTER TABLE bank ADD COLUMN cardno VARCHAR(20) NULL;
UPDATE bank b
JOIN (
    SELECT pin, MIN(cardno) AS cardno FROM login
    GROUP BY pin HAVING COUNT(*) = 1
) l ON b.pin = l.pin
SET b.cardno = l.cardno;
