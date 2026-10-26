USE bankdb;

-- Every unresolved record, identified without exposing historical PINs.
SELECT source_table, source_id, reason
FROM migration_issues
ORDER BY source_table, source_id;

-- Original transaction values remain available for explicit reconciliation.
SELECT b.id, b.cardno, b.date, b.type, b.amount, i.reason
FROM bank_legacy b
JOIN migration_issues i ON i.source_table = 'bank_legacy' AND i.source_id = CAST(b.id AS CHAR)
ORDER BY b.id;

-- Net imported movement is evidence, not an inferred opening/current balance.
SELECT a.account_id, a.formno, a.balance, a.balance_verified,
       COALESCE(SUM(CASE t.transaction_type WHEN 'DEPOSIT' THEN t.amount ELSE -t.amount END), 0.00)
           AS imported_net_movement
FROM accounts a
LEFT JOIN transactions t ON t.account_id = a.account_id AND t.legacy_bank_id IS NOT NULL
WHERE a.balance_verified = FALSE
GROUP BY a.account_id, a.formno, a.balance, a.balance_verified
ORDER BY a.account_id;

-- Imported + pending must equal archived; every archived row remains retained.
SELECT (SELECT COUNT(*) FROM bank_legacy) AS archived_rows,
       (SELECT COUNT(*) FROM transactions WHERE legacy_bank_id IS NOT NULL) AS imported_rows,
       (SELECT COUNT(*) FROM bank_legacy b LEFT JOIN transactions t ON t.legacy_bank_id = b.id
        WHERE t.transaction_id IS NULL) AS pending_rows;
