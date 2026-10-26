# Upgrading existing plaintext PINs

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

5. Start the new application and log in with the same PIN. The new code uses
   only `pin_hash`; old application binaries must not be used after this upgrade.

The process is offline because the old application writes plaintext while the
new application writes hashes. Do not enable JDBC protocol/parameter tracing or
print query result values during conversion. Existing backups and historical
database logs may still contain the old plaintext; protect them under your
existing backup/log retention policy.
