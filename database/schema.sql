CREATE DATABASE IF NOT EXISTS bankdb;
USE bankdb;

CREATE TABLE IF NOT EXISTS signup (
    formno VARCHAR(20) PRIMARY KEY,
    name VARCHAR(20) NOT NULL,
    fname VARCHAR(20) NOT NULL,
    dob VARCHAR(20),
    gender VARCHAR(20),
    email VARCHAR(20),
    marital VARCHAR(20),
    address VARCHAR(80),
    city VARCHAR(20),
    pincode VARCHAR(10),
    state VARCHAR(30)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS signuptwo (
    formno VARCHAR(20) PRIMARY KEY,
    religion VARCHAR(20),
    category VARCHAR(20),
    income VARCHAR(20),
    education VARCHAR(20),
    occupation VARCHAR(20),
    pan VARCHAR(20),
    aadhar VARCHAR(20),
    isSenior VARCHAR(3) NULL DEFAULT NULL,
    FOREIGN KEY (formno) REFERENCES signup(formno)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS login (
    formno  VARCHAR(20),
    cardno VARCHAR(20)  PRIMARY KEY,
    pin_hash VARCHAR(255) NOT NULL,
    CONSTRAINT uq_login_formno UNIQUE (formno),
    FOREIGN KEY (formno) REFERENCES signup(formno)
) ENGINE=InnoDB;

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

