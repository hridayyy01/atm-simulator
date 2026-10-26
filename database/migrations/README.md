# Database upgrade instructions

## Existing plaintext PINs

Fresh installations use `database/schema.sql` and skip migrations 003 and 004.
For an existing installation, complete migrations 001 (if needed) and 002 first.
The steps below preserve accounts, cards and existing four-digit PINs, including
leading zeros. They do not print PINs or require entering them on a command line.

1. Stop every application instance and keep it stopped through finalization.
   Retain a protected database backup for recovery; it contains legacy secrets.
   Compile the new Java sources using the root README instructions.

2. With `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` set for the existing database,
   prepare the columns once. For local MySQL, from the project root:

   ```sh
   mysql -u "$DB_USERNAME" -p < database/migrations/003_prepare_pin_hashes.sql
   ```

   Add MySQL host/port options if required. This adds the hash column and allows
   clearing `login.pin`. It also adds `bank.cardno` and maps legacy transaction
   rows to cards only where their old PIN identifies exactly one card. PINs are
   not unique account identifiers: ambiguous/unmatched transaction rows remain
   present with a NULL card reference. No transaction rows are deleted.

3. Run the Java converter with the same environment configuration:

   ```sh
   JDBC_JAR="../../mysql-connector-j-9.7.0/mysql-connector-j-9.7.0.jar"
   java -cp "out:$JDBC_JAR" atm.tools.MigratePins
   ```

   It locks the login rows, derives a fresh salted hash for each legacy PIN and
   clears that row's plaintext `pin` in one InnoDB transaction. Already-converted
   rows are skipped, making the converter safe to rerun before finalization.
   Invalid legacy PINs or conflicting existing hashes cause rollback and require
   administrator review; do not replace them with a shared/default PIN. A failed
   or unconfirmed run must be resolved by rerunning successfully before step 4.
   No PINs, hash values or driver exception details are written to its output.

4. After the converter reports success, finalize once:

   ```sh
   mysql -u "$DB_USERNAME" -p < database/migrations/004_finalize_pin_hashes.sql
   ```

   This requires a non-null hash for every login and drops both `login.pin` and
   the unused `bank.pin` plaintext column. It preserves account and transaction
   rows. Do not use `mysql --force`: a completeness-check failure must stop the
   script before column removal. Preparation/finalization SQL files are one-time
   migrations; only the Java converter is rerunnable between these steps.

5. Continue with the accounts migration below before starting this version. The
   new code uses only `pin_hash`; old application binaries must not be used after
   this upgrade. PINs themselves remain unchanged.

The process is offline because the old application writes plaintext while the
new application writes hashes. Do not enable JDBC protocol/parameter tracing or
print query result values during conversion. Existing backups and historical
database logs may still contain the old plaintext; protect them under your
existing backup/log retention policy.

## Accounts and transactions

Fresh installs use `database/schema.sql` directly. For an existing database,
finish the PIN upgrade above first, stop all application instances, and compile
the current sources using the root README commands. Keep the existing protected
backup through reconciliation. MySQL 8.0.16+ and InnoDB are required.

From the project root, using the configured database environment variables:

```sh
# One-time schema preparation, after migrations 001-004.
mysql -u "$DB_USERNAME" -p < database/migrations/005_accounts_and_transactions.sql

# Transactional converter; safe to rerun after preparation.
JDBC_JAR="../../mysql-connector-j-9.7.0/mysql-connector-j-9.7.0.jar"
java -cp "out:$JDBC_JAR" atm.tools.MigrateAccounts

# Review every unresolved record and the archive/import/pending row counts.
mysql -u "$DB_USERNAME" -p < database/reports/legacy_account_migration.sql
```

Supply MySQL host/port options for a remote server. Do not use `mysql --force`.
The SQL preparation creates `accounts`, `transactions` and `migration_issues`,
then renames `bank` to `bank_legacy` without changing any row values. Do not rerun
the SQL file once the archive exists; rerun only the Java converter as needed.
If preparation stops partway, inspect the schema before resuming. Neither the
converter nor a report modifies or deletes archived rows.

The converter creates one UUID account per linked legacy application, reusing
existing account IDs on reruns. Logins without a valid application are reported
as `UNRESOLVED_APPLICATION`. New legacy accounts receive an explicitly unverified
zero balance and an `OPENING_BALANCE_UNVERIFIED` issue. Existing accounts and
balances are not overwritten. Signup creates new, verified zero-balance accounts
inside its transaction after the upgrade.

An archived transaction is imported only when all of the following hold:

- Its card resolves to exactly one account. Missing card references, including
  ambiguous PIN ownership left by the previous migration, are reported.
- Its amount is an unsigned plain decimal, positive and exactly representable
  as `DECIMAL(19,2)`. Whitespace and trailing zero precision can be removed
  losslessly. Currency symbols, grouping commas, signs, exponents, overflow and
  fractional cents are reported, not guessed or rounded.
- Its type is `Deposit`, `Withdrawal`, or the legacy alias `Withdraw`, ignoring
  case and surrounding whitespace. Other types are reported.
- Its date is ISO-8601 with an explicit offset, for example
  `2026-10-26T12:00:00+05:30` or `2026-10-26T06:30:00Z`. It is normalized to UTC
  without losing precision, within MySQL's datetime range. Dates without offsets,
  abbreviations such as `IST`, invalid calendar dates, and precision beyond
  microseconds that cannot be represented exactly are reported for review.

`migration_issues` stores the source table, source row ID and all applicable
reason codes. Valid imported rows retain `legacy_bank_id`; its unique constraint
makes reruns skip existing imports. All account creation, converted transactions
and issue reports commit together or roll back together. A successful converter
exit means conversion completed, **not** that unresolved records or balances
have been reconciled. The report must satisfy `archived_rows = imported_rows +
pending_rows`.

Do not treat an unverified placeholder balance as spendable funds. Review the
archive, unresolved ownership and history, and an authoritative opening/current
balance before updating `accounts.balance` and `balance_verified` together in a
reviewed administrator transaction. The report's imported net movement is not
an inferred balance. Resolve missing information through a reviewed follow-up
migration into the typed tables using each original `legacy_bank_id`; keep the
archive unchanged, and close the corresponding issue only after verification.
No ambiguous row is deleted or assigned to an arbitrary account.
