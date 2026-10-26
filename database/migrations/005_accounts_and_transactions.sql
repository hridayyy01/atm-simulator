-- Apply once after migrations 001-004, with all application instances stopped.
-- New tables retain stable IDs; the original bank rows are archived unchanged.
USE bankdb;

CREATE TABLE IF NOT EXISTS accounts (
    account_id CHAR(36) PRIMARY KEY,
    formno VARCHAR(20) NOT NULL,
    balance DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    balance_verified BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_accounts_formno UNIQUE (formno),
    CONSTRAINT fk_accounts_signup FOREIGN KEY (formno) REFERENCES signup(formno),
    CONSTRAINT chk_accounts_balance CHECK (balance >= 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS transactions (
    transaction_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id CHAR(36) NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    occurred_at DATETIME(6) NOT NULL COMMENT 'UTC',
    legacy_bank_id BIGINT NULL,
    CONSTRAINT uq_transactions_legacy_bank UNIQUE (legacy_bank_id),
    CONSTRAINT fk_transactions_account FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT chk_transactions_amount CHECK (amount > 0),
    CONSTRAINT chk_transactions_type CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL')),
    INDEX idx_transactions_account_time (account_id, occurred_at, transaction_id)
) ENGINE=InnoDB;


CREATE TABLE IF NOT EXISTS migration_issues (
    source_table VARCHAR(32) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    PRIMARY KEY (source_table, source_id)
) ENGINE=InnoDB;

RENAME TABLE bank TO bank_legacy;
-- Next run atm.tools.MigrateAccounts, then database/reports/legacy_account_migration.sql.
