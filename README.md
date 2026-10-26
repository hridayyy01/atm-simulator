# ATM simulator

Java Swing ATM application with registration, login and account operations.

## Project layout

```text
src/main/java/atm/
  App.java                 Application entry point
  db/Conn.java             JDBC connection configuration
  dao/                     Registration, authentication, accounts and history SQL
  model/                   Plain Java data models and explicit login sessions
  security/                JDK PIN hashing and verification
  tools/                   Offline PIN and account upgrade utilities
  ui/                      Swing screens and shared UI constants
src/test/java/atm/dao/      Headless DAO regression tests
src/test/java/atm/security/ PIN hashing regression tests
src/test/java/atm/tools/    Legacy money/date conversion tests
src/main/resources/icons/  Application images
database/schema.sql        Existing database schema
database/migrations/       Changes for existing databases
database/reports/          Legacy reconciliation reports
```

## Configuration

Set these environment variables in the shell or IDE run configuration before
starting the application:

| Variable | Required value |
| --- | --- |
| `DB_URL` | JDBC URL for the database created from `database/schema.sql` |
| `DB_USERNAME` | Database username |
| `DB_PASSWORD` | Database password |

All three values are required and must be non-blank. Keep credentials outside
source files. The application reports missing configuration by variable name.

## Database setup and upgrades

For a fresh installation, use `database/schema.sql`. For an existing installation
that lacks `signuptwo.isSenior`, apply
`database/migrations/001_add_is_senior.sql` once. Both define the same nullable
`VARCHAR(3)` column for the application's `Yes`/`No` values. Existing rows retain
their data and receive `NULL` for the previously unrecorded senior status.

For local MySQL, with `DB_USERNAME` configured, run the appropriate command from
the project root and enter the database password when prompted:

```sh
# Fresh installation
mysql -u "$DB_USERNAME" -p < database/schema.sql

# Existing installation missing isSenior (run once)
mysql -u "$DB_USERNAME" -p < database/migrations/001_add_is_senior.sql
```

Add your MySQL host and port options if using a remote server. Running the fresh
schema against existing tables does not upgrade them; use the migration instead.
Skip this migration if `isSenior` already exists, including on fresh installs.

Existing installations must also apply `database/migrations/002_atomic_registration.sql`
once before using the new registration flow:

```sh
mysql -u "$DB_USERNAME" -p < database/migrations/002_atomic_registration.sql
```

This migration uses InnoDB for all three registration tables and adds a unique
constraint on `login.formno`, limiting each application to one card. The existing
`login.cardno` primary key enforces card uniqueness. Existing values are retained.
The migration reports historical applications with multiple cards and fails the
unique-constraint addition if any remain; resolve those records explicitly before
rerunning it. Fresh installations already include these constraints and engines.

Before running this version against an existing plaintext-PIN installation,
follow the [PIN upgrade instructions](database/migrations/README.md). This adds
`login.pin_hash`, converts existing PINs with the Java tool, and removes legacy
plaintext columns. Fresh installations use the updated schema directly and skip
migrations 003 and 004.

Existing installations must then apply `database/migrations/005_accounts_and_transactions.sql`
once and run `atm.tools.MigrateAccounts` before starting this version. Follow the
[account and transaction upgrade instructions](database/migrations/README.md#accounts-and-transactions).
This preserves `bank` as `bank_legacy`, imports only unambiguous history, and
persists a report of unresolved records. Fresh installations skip all migrations.

## Accounts and transactions

Each account has a UUID account ID independent of its application, card and PIN.
Registration creates its account in the same JDBC transaction as the application
and credentials. New accounts start with a verified `0.00` balance. Authentication
returns the stable account ID; `AccountDao` and `TransactionDao` read accounts and
history by that ID.

Balances and amounts are `DECIMAL(19,2)` in MySQL and `BigDecimal` in Java, using
JDBC `setBigDecimal`/`getBigDecimal`. Values requiring rounding or exceeding the
column's precision are rejected. Transaction amounts are positive; `DEPOSIT` and
`WITHDRAWAL` supply the direction. Each transaction references an account with a
foreign key and stores an explicit UTC `DATETIME(6)`, represented as `Instant` in
Java. The JDBC conversion uses `LocalDateTime` in UTC, independent of the machine's
timezone. No monetary value passes through `double` or `float`.

Migrated accounts have `balance_verified = FALSE`: their zero balance is a
placeholder, not an assertion of available funds. An administrator must reconcile
the retained history and establish the authoritative balance before marking it
verified. Any future balance-changing operation must reject unverified balances.
The migration report includes imported net movement as reconciliation evidence;
it does not infer an opening balance or silently discard ambiguous records.

The schema targets MySQL 8.0.16+ with InnoDB and enforced check constraints.
See MySQL's [exact numeric types](https://dev.mysql.com/doc/refman/8.4/en/precision-math-numbers.html)
and [date/time types](https://dev.mysql.com/doc/refman/8.4/en/datetime.html), and
JDK [BigDecimal](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html).

## Login and ATM dashboard

The application starts at login. **Register** opens a new in-memory application;
successful registration returns to a blank login screen. Closing a registration
screen cancels that draft and returns to login.

Successful authentication creates an explicit `Session` with the account ID and
holder identity. It contains no PIN. The dashboard receives that session and its
DAO dependencies through its constructor. All account operations use the session's
account ID, with no global current-user state.

The dashboard provides these actions:

- **Deposit / Withdrawal:** accept a positive exact decimal amount, lock the account
  row, and commit the balance update and ledger entry together. Withdrawals reject
  insufficient funds; both actions reject unverified legacy balances. Failed
  writes roll back. Monetary operations are never automatically retried after a
  database error; check balance and mini statement if a commit was unconfirmed.
- **Balance:** fetch the latest balance; unverified legacy accounts display a
  reconciliation notice instead of the placeholder zero.
- **Mini Statement:** show this account's ten newest transactions, newest first,
  with UTC timestamps, transaction types and exact decimal amounts.
- **Change PIN:** require the current PIN, a different four-digit new PIN and its
  confirmation. The DAO locks the credentials, verifies the current salted hash
  and stores a new salted hash. All three inputs use password fields; temporary
  PIN buffers are cleared. The new PIN is required for the next login.
- **Logout:** invalidate the session, dispose the dashboard and create a fresh login
  screen with empty fields. Closing the dashboard performs the same logout.

Login and dashboard database work runs in `SwingWorker`s, keeping the event thread
responsive. Actions are disabled while work is in progress; logout waits for a
pending operation to finish. Disposed screens ignore stale results. Dialogs and
screen changes run on the event thread. `AppNavigation` is a small concrete factory
that wires dependencies and keeps one application screen visible.

## PIN storage

Login uses `JPasswordField` and passes a `char[]` to the DAO. New PINs are generated
using `SecureRandom`. Only salted PBKDF2-HMAC-SHA256 hashes are stored in
`login.pin_hash`: 600,000 iterations, a fresh 16-byte salt and a 32-byte derived
key. The encoded value stores the algorithm, work factor, salt and derived key.
Verification uses JDK `MessageDigest.isEqual` and rejects plaintext, malformed
encodings and unsupported parameters. There is no plaintext authentication fallback.

These parameters follow the [OWASP PBKDF2 guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2).
Implementation uses the JDK's [PBKDF2WithHmacSHA256](https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html)
and [PBEKeySpec](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/spec/PBEKeySpec.html).

Login buffers and the password field are cleared after each attempt. The generated
initial PIN is shown once in the account-created dialog, then cleared from the
shared credentials object. Credential objects redact their text representation.
The application and migration tool do not log PINs, hashes, SQL parameters or raw
exception messages/stack traces. Keep driver protocol/parameter tracing disabled
during the upgrade, since the converter must read legacy plaintext values.

## Registration flow

The signup screens share one in-memory `Registration`. Pages 1 and 2 validate and
collect details without writing to the database. The final **Submit Application**
button saves `signup`, `signuptwo`, `accounts` and `login` in one JDBC transaction, returning
credentials only after a successful commit. An insert failure rolls back all
four writes and leaves the final screen open with the collected details intact.

Application IDs contain 20 random base-32 characters (100 random bits) and fit
the existing `VARCHAR(20)` columns. Cards and PINs use `SecureRandom`; their
existing 16-digit and four-digit formats are retained. Uniqueness is enforced
by the database. Duplicate application IDs, account IDs or cards trigger a full rollback and
retry, with at most five total attempts per submission. Other errors are not
automatically retried.

The final button is disabled during submission and after success. Concurrent or
repeated submissions of the same shared registration return its already-committed
credentials without another write. If a commit acknowledgement or rollback fails,
that registration is blocked from resubmission because the outcome cannot be
confirmed. Check its application ID in the database before starting another
application. Closing the application before final submission discards the draft.

## Compile and run

Use JDK 21 and the local MySQL connector JAR. From the project root on macOS/Linux:

```sh
JDBC_JAR="../../mysql-connector-j-9.7.0/mysql-connector-j-9.7.0.jar"
mkdir -p out
javac -cp "$JDBC_JAR" -d out src/main/java/atm/App.java src/main/java/atm/db/*.java src/main/java/atm/dao/*.java src/main/java/atm/model/*.java src/main/java/atm/security/*.java src/main/java/atm/tools/*.java src/main/java/atm/ui/*.java
cp -R src/main/resources/. out/
java -cp "out:$JDBC_JAR" atm.App
```

Adjust `JDBC_JAR` if the connector is installed elsewhere. In IntelliJ, the module
marks `src/main/java` as sources and `src/main/resources` as resources. Run
`atm.App` with the same environment variables and the connector on its classpath.

## DAO tests

After compiling the application, run the headless checks below. They use a test
JDBC driver and dummy credentials; they do not connect to MySQL or open windows.
They cover transaction rollback at every insert, uniqueness collision limits,
concurrent submissions, uncertain commits, field mapping, session invalidation,
deposits/withdrawals, ledger rollback, mini statement limits, PIN changes and JDBC
cleanup. These checks use scripted JDBC, not a running MySQL server or graphical UI.

```sh
javac -cp out -d out src/test/java/atm/dao/DaoTest.java src/test/java/atm/security/PinHasherTest.java src/test/java/atm/tools/AccountMigrationTest.java
java -Djava.awt.headless=true -cp out atm.security.PinHasherTest
java -Djava.awt.headless=true -cp out atm.tools.AccountMigrationTest
DB_URL=jdbc:atm-dao-test DB_USERNAME=test-only-user DB_PASSWORD=test-only-password java -Djava.awt.headless=true -cp out atm.dao.DaoTest
```

`App` creates the four concrete DAOs and passes them to `AppNavigation`, which
injects each screen's dependencies through its constructor.
DAOs own JDBC resources and return plain data; validation, dialogs and navigation
remain in the Swing screens.

For a manual desktop check after database setup: launch `atm.App`, open registration
from login, register and return to login, authenticate, deposit and withdraw funds,
check balance and mini statement, change PIN, then log out. Confirm the new login
fields are empty, the old PIN is rejected, and the new PIN opens a new session.
