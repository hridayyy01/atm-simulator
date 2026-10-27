package atm.dao;

import atm.model.AdditionalDetails;
import atm.model.AuthenticatedUser;
import atm.model.CardCredentials;
import atm.model.Registration;
import atm.model.PersonalDetails;
import atm.security.PinHasher;
import atm.tools.MigratePins;
import atm.tools.MigrateAccounts;
import atm.service.AccountService;
import atm.service.AccountOperationException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.io.StringReader;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/** Headless tests using a scripted JDBC driver, without a database or Swing. */
public final class DaoTest {
    private static final String TEST_URL = "jdbc:atm-dao-test";
    private static final RegistrationDao REGISTRATION = new RegistrationDao();
    private static final AuthenticationDao AUTHENTICATION = new AuthenticationDao();
    private static final PersonalDetails PERSONAL = new PersonalDetails("Test Name",
            "Test Parent", "Female", "test@example.invalid", "Married", "Test Address",
            "Test City", "123456", "Test State");
    private static final String PIN_FIXTURE = "pbkdf2-sha256$600000$AAECAwQFBgcICQoLDA0ODw==$mj8Uk1lF7SLS70PvzppZDceQEIBGQtufTiYwfc03sp4=";
    private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";
    private static Script script;

    public static void main(String[] args) throws Exception {
        check(TEST_URL.equals(System.getenv("DB_URL")), "Use the test JDBC URL from README.md");
        Driver driver = new TestDriver();
        DriverManager.registerDriver(driver);
        try {
            if (args.length == 1 && args[0].equals("--migration-failure-probe")) {
                Step select = migrationSelect();
                select.failure = new SQLException("Driver error containing dummy PIN 6789");
                script = new Script(select, migrationUpdate());
                MigratePins.main(new String[0]);
                return;
            }
            sessionLifecycle();
            bankingActions();
            pinChange();
            accountAndTransactions();
            accountMigration();
            pinMigration();
            migrationLogSafety();
            draftAndCompleteness();
            successfulSubmission();
            authentication();
            rollbackFailures();
            retryAfterRollback();
            collisionRetries();
            concurrentSubmission();
            uncertainOutcomes();
            System.out.println("PASS: atomic registration, rollback, bounded collision retries, duplicate submissions, authentication and cleanup.");
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    private static void sessionLifecycle() {
        atm.model.Session first = new atm.model.Session(new AuthenticatedUser(ACCOUNT_ID, "1234", "First User"));
        atm.model.Session second = new atm.model.Session(new AuthenticatedUser("another-account", "5678", "Second User"));
        check(first.isActive() && first.getAccountId().equals(ACCOUNT_ID) && first.getFormno().equals("1234") && first.getName().equals("First User"),
                "Carry only the authenticated account identity into a session");
        first.logout();
        first.logout();
        check(!first.isActive() && second.isActive() && second.getAccountId().equals("another-account"),
                "Logout invalidates only that session; later users have independent identity");
        try { first.getAccountId(); throw new AssertionError("Reject stale account access after logout"); }
        catch (IllegalStateException expected) { }
        try { first.getName(); throw new AssertionError("Reject stale user access after logout"); }
        catch (IllegalStateException expected) { }
        try { first.getFormno(); throw new AssertionError("Reject stale application identity after logout"); }
        catch (IllegalStateException expected) { }
    }

    private static atm.model.Session querySession() {
        return new atm.model.Session(new AuthenticatedUser(ACCOUNT_ID, "1234", "Test User"));
    }

    private static atm.model.Account postTransaction(BigDecimal amount, atm.model.TransactionType type) throws SQLException {
        AccountService service = new AccountService(new AccountDao(), new TransactionDao());
        atm.model.Session session = new atm.model.Session(new AuthenticatedUser(ACCOUNT_ID, "1234", "Test User"));
        return type == atm.model.TransactionType.DEPOSIT ? service.deposit(session, amount) : service.withdraw(session, amount);
    }

    private static Step lockedAccount(String balance, boolean verified) {
        Step lookup = new Step("SELECT account_id, formno, balance, balance_verified FROM accounts ", 0, true);
        lookup.columns.putAll(Map.of("account_id", ACCOUNT_ID, "formno", "1234", "balance", balance,
                "balance_verified", Boolean.toString(verified)));
        return lookup;
    }

    private static void bankingActions() throws Exception {
        for (atm.model.TransactionType type : atm.model.TransactionType.values()) {
            Step lookup = lockedAccount("100.10", true);
            Step update = new Step("UPDATE accounts SET balance = ? WHERE account_id = ?", 1, false);
            Step ledger = new Step("INSERT INTO transactions ", 1, false);
            script = new Script(lookup, update, ledger);
            var result = postTransaction(new BigDecimal("0.20"), type);
            BigDecimal expected = new BigDecimal(type == atm.model.TransactionType.DEPOSIT ? "100.30" : "99.90");
            check(result.balance().equals(expected) && update.typedParameters.get(1).equals(expected), "Update balances with exact decimal arithmetic");
            check(lookup.actualSql.endsWith("WHERE account_id = ? AND formno = ? FOR UPDATE")
                    && lookup.parameters.equals(List.of(ACCOUNT_ID, "1234")), "Lock only the session's owned account");
            check(update.actualSql.endsWith("WHERE account_id = ? AND formno = ?")
                    && update.parameters.get(2).equals("1234"), "Constrain balance writes to the same owner");
            check(ledger.parameters.get(0).equals(ACCOUNT_ID) && ledger.parameters.get(1).equals(type.name())
                    && ledger.typedParameters.get(3).equals(new BigDecimal("0.20"))
                    && ((LocalDateTime) ledger.typedParameters.get(4)).getNano() % 1000 == 0,
                    "Record the account-scoped amount, type and microsecond timestamp");
            check(script.commits == 1 && script.rollbacks == 0 && script.persisted.size() == 2,
                    "Commit the balance and statement entry together");
            script.verifyClosed();
        }
        for (AccountOperationException.Reason reason : AccountOperationException.Reason.values()) {
            Step lookup = lockedAccount(reason == AccountOperationException.Reason.BALANCE_LIMIT
                    ? "99999999999999999.99" : "1.00", reason != AccountOperationException.Reason.BALANCE_UNVERIFIED);
            if (reason == AccountOperationException.Reason.ACCOUNT_NOT_FOUND) lookup.rows = List.of();
            script = new Script(lookup);
            try {
                postTransaction(new BigDecimal("2.00"),
                        reason == AccountOperationException.Reason.INSUFFICIENT_FUNDS ? atm.model.TransactionType.WITHDRAWAL : atm.model.TransactionType.DEPOSIT);
                throw new AssertionError("Expected account operation rejection");
            } catch (AccountOperationException expected) {
                check(expected.getReason() == reason, "Report the safe business reason");
            }
            check(script.rollbacks == 1 && script.commits == 0 && script.persisted.isEmpty(), "Rejected operations must not change balances or history");
            script.verifyClosed();
        }
        for (boolean failLedger : new boolean[]{false, true}) {
            Step lookup = lockedAccount("1.00", true);
            Step update = new Step("UPDATE accounts SET balance = ? WHERE account_id = ?", 1, false);
            Step ledger = new Step("INSERT INTO transactions ", 1, false);
            if (failLedger) ledger.failure = new SQLException("Ledger insert failed");
            else update.failure = new SQLException("Balance update failed");
            script = failLedger ? new Script(lookup, update, ledger) : new Script(lookup, update);
            expectSqlFailure(() -> postTransaction(new BigDecimal("0.20"), atm.model.TransactionType.DEPOSIT));
            check(script.rollbacks == 1 && script.commits == 0 && script.persisted.isEmpty() && script.pending.isEmpty(),
                    "Roll back partial balance/ledger changes on SQL failures");
        }
        script = new Script(lockedAccount("1.00", true), new Step("UPDATE accounts SET balance = ?", 1, false),
                new Step("INSERT INTO transactions ", 1, false));
        script.commitFailure = new SQLException("Lost commit acknowledgement");
        script.commitBeforeFailure = true;
        expectSqlFailure(() -> postTransaction(new BigDecimal("0.20"), atm.model.TransactionType.DEPOSIT));
        check(script.commits == 1 && script.persisted.size() == 2 && script.connections == 1,
                "Never automatically repeat a monetary operation after an uncertain commit");

        for (String invalid : new String[]{"0", "-1", "0.001", "100000000000000000.00"}) {
            script = new Script();
            try { postTransaction(new BigDecimal(invalid), atm.model.TransactionType.DEPOSIT); throw new AssertionError("Reject invalid amounts"); }
            catch (IllegalArgumentException expected) { check(script.connections == 0, "Validate amounts before opening JDBC"); }
        }
        Step history = new Step("SELECT t.transaction_id, t.account_id, t.transaction_type, t.amount, t.occurred_at FROM transactions t ", 0, false);
        script = new Script(history);
        check(new TransactionDao().findRecentForSession(querySession()).isEmpty(), "Support an empty mini statement");
        check(history.actualSql.endsWith("ORDER BY t.occurred_at DESC, t.transaction_id DESC LIMIT 10")
                && history.actualSql.contains("WHERE t.account_id = ? AND a.formno = ?")
                && history.parameters.equals(List.of(ACCOUNT_ID, "1234")), "Limit mini statements to this owner's ten newest entries");
        script.verifyClosed();
    }

    private static void pinChange() throws Exception {
        char[] current = {'0','0','4','2'}, next = {'9','8','7','6'};
        try {
            Step lookup = new Step("SELECT l.cardno, l.pin_hash FROM login l ", 0, true);
            lookup.columns.putAll(Map.of("cardno", "0000000000000001", "pin_hash", PIN_FIXTURE));
            Step update = new Step("UPDATE login SET pin_hash = ? WHERE cardno = ?", 1, false);
            script = new Script(lookup, update);
            check(AUTHENTICATION.changePin(ACCOUNT_ID, current, next), "Allow a verified PIN change");
            check(lookup.actualSql.endsWith("FOR UPDATE") && lookup.parameters.equals(List.of(ACCOUNT_ID)), "Verify the PIN under the session account's lock");
            String hash = update.parameters.get(0);
            check(PinHasher.verify(next, hash) && !PinHasher.verify(current, hash)
                    && update.parameters.get(1).equals("0000000000000001"), "Update only the selected account's salted PIN hash");
            check(script.commits == 1 && script.rollbacks == 0, "Commit a successful PIN change");
            script.verifyClosed();
            for (char[] supplied : new char[][]{next, current}) {
                Step login = new Step("SELECT l.formno, s.name, l.pin_hash, a.account_id FROM login l ", 0, true);
                login.columns.putAll(Map.of("account_id", ACCOUNT_ID, "formno", "1234", "name", "Test User", "pin_hash", hash));
                script = new Script(login);
                check((AUTHENTICATION.authenticate("0000000000000001", supplied) != null) == (supplied == next),
                        "After a PIN change, accept the new PIN and reject the old one");
                script.verifyClosed();
            }
            lookup = new Step("SELECT l.cardno, l.pin_hash FROM login l ", 0, true);
            lookup.columns.putAll(Map.of("cardno", "0000000000000001", "pin_hash", PIN_FIXTURE));
            script = new Script(lookup);
            check(!AUTHENTICATION.changePin(ACCOUNT_ID, next, current), "Reject an incorrect current PIN without writing a replacement");
            check(script.rollbacks == 1 && script.commits == 0 && script.persisted.isEmpty(), "Do not commit failed PIN verification");
            script.verifyClosed();
            lookup = new Step("SELECT l.cardno, l.pin_hash FROM login l ", 0, true);
            lookup.columns.putAll(Map.of("cardno", "0000000000000001", "pin_hash", PIN_FIXTURE));
            update = new Step("UPDATE login SET pin_hash = ? WHERE cardno = ?", 1, false);
            update.failure = new SQLException("PIN update failed");
            script = new Script(lookup, update);
            expectSqlFailure(() -> AUTHENTICATION.changePin(ACCOUNT_ID, current, next));
            check(script.commits == 0 && script.rollbacks == 1, "Roll back a failed PIN update");
        } finally {
            Arrays.fill(current, '\0'); Arrays.fill(next, '\0');
        }
    }

    private static void accountAndTransactions() throws Exception {
        Step lookup = new Step("SELECT account_id, formno, balance, balance_verified FROM accounts ", 0, true);
        lookup.columns.putAll(Map.of("account_id", ACCOUNT_ID, "formno", "1234", "balance", "9007199254740993.01", "balance_verified", "false"));
        script = new Script(lookup);
        atm.model.Account account = new AccountDao().findForSession(querySession());
        check(account.balance().equals(new BigDecimal("9007199254740993.01")) && !account.balanceVerified(),
                "Read exact DECIMAL balances and preserve the unverified legacy state");
        check(lookup.actualSql.endsWith("WHERE account_id = ? AND formno = ?")
                && lookup.parameters.equals(List.of(ACCOUNT_ID, "1234")), "Find accounts by stable ID and authenticated ownership");
        script.verifyClosed();
        Step history = new Step("SELECT t.transaction_id, t.account_id, t.transaction_type, t.amount, t.occurred_at FROM transactions t ", 0, true);
        history.rows = List.of(
                Map.of("transaction_id", "1", "account_id", ACCOUNT_ID, "transaction_type", "DEPOSIT", "amount", "0.10", "occurred_at", "2026-10-26T12:00:00.123456"),
                Map.of("transaction_id", "2", "account_id", ACCOUNT_ID, "transaction_type", "WITHDRAWAL", "amount", "0.20", "occurred_at", "2026-10-26T13:00:00"));
        script = new Script(history);
        var transactions = new TransactionDao().findForSession(querySession());
        check(transactions.size() == 2 && transactions.get(0).amount().equals(new BigDecimal("0.10"))
                && transactions.get(1).type() == atm.model.TransactionType.WITHDRAWAL
                && transactions.get(0).occurredAt().equals(Instant.parse("2026-10-26T12:00:00.123456Z")),
                "Read typed transaction amounts and UTC timestamps without floating-point conversion");
        check(history.actualSql.contains("WHERE t.account_id = ? AND a.formno = ?")
                && history.actualSql.endsWith("ORDER BY t.occurred_at, t.transaction_id")
                && history.parameters.equals(List.of(ACCOUNT_ID, "1234")), "Scope full history to the owner and preserve timestamp/ID order");
        script.verifyClosed();
    }

    private static void accountMigration() throws Exception {
        Step issues = new Step("INSERT INTO migration_issues ", 1, false);
        Step forms = new Step("SELECT DISTINCT l.formno FROM login l ", 0, false);
        Step create = account();
        Step orphans = new Step("SELECT l.cardno FROM login l LEFT JOIN signup ", 0, false);
        Step legacy = new Step("SELECT b.id, b.cardno, b.date, b.type, b.amount, ", 0, true);
        legacy.rows = List.of(
                Map.of("id", "1", "cardno", "0000000000000001", "date", "2026-10-26T12:00:00+05:30", "type", "Deposit", "amount", "9007199254740993.01"),
                Map.of("id", "2", "date", "26/10/2026", "type", "unknown", "amount", "1.005"));
        Step resolve = new Step("SELECT a.account_id FROM login l ", 0, true);
        resolve.columns.put("account_id", ACCOUNT_ID);
        Step insert = new Step("INSERT INTO transactions ", 1, false);
        Step count = new Step("SELECT COUNT(*) AS issue_count FROM migration_issues", 0, true);
        count.columns.put("issue_count", "1");
        script = new Script(issues, forms, create, orphans, legacy, resolve, insert, count);
        MigrateAccounts.Report report;
        try (Connection con = script.connection()) { report = MigrateAccounts.migrate(con); }
        check(report.accountsCreated() == 0 && report.transactionsImported() == 1 && report.recordsForReview() == 1,
                "Import only unambiguous legacy records and report the rest");
        check(insert.typedParameters.get(3).equals(new BigDecimal("9007199254740993.01"))
                && insert.typedParameters.get(4).equals(LocalDateTime.parse("2026-10-26T06:30:00"))
                && insert.typedParameters.get(5).equals(1L), "Persist exact migrated amounts, UTC time and the source row ID");
        check(issues.parameters.get(0).equals("bank_legacy") && issues.parameters.get(1).equals("2")
                && issues.parameters.get(2).contains("UNRESOLVED_OR_AMBIGUOUS_ACCOUNT")
                && issues.parameters.get(2).contains("INVALID_AMOUNT")
                && issues.parameters.get(2).contains("UNSUPPORTED_TYPE")
                && issues.parameters.get(2).contains("AMBIGUOUS_OR_INVALID_TIMESTAMP"), "Report all ambiguity reasons without guessing");
        check(script.commits == 1 && script.rollbacks == 0, "Commit imports and reports together");
        script.verifyClosed();

        issues = new Step("INSERT INTO migration_issues ", 1, false);
        forms = new Step("SELECT DISTINCT l.formno FROM login l ", 0, false);
        create = account();
        orphans = new Step("SELECT l.cardno FROM login l LEFT JOIN signup ", 0, false);
        legacy = new Step("SELECT b.id, b.cardno, b.date, b.type, b.amount, ", 0, true);
        legacy.columns.put("imported_id", "1");
        resolve = new Step("SELECT a.account_id FROM login l ", 0, false);
        insert = new Step("INSERT INTO transactions ", 1, false);
        count = new Step("SELECT COUNT(*) AS issue_count FROM migration_issues", 0, true);
        count.columns.put("issue_count", "0");
        script = new Script(issues, forms, create, orphans, legacy, resolve, insert, count);
        try (Connection con = script.connection()) { report = MigrateAccounts.migrate(con); }
        check(report.accountsCreated() == 0 && report.transactionsImported() == 0 && script.persisted.isEmpty(),
                "Rerunning migration must preserve stable IDs and avoid duplicate imports");
        script.verifyClosed();

        issues = new Step("INSERT INTO migration_issues ", 1, false);
        forms = new Step("SELECT DISTINCT l.formno FROM login l ", 0, true);
        forms.columns.put("formno", "legacy-form");
        create = account();
        orphans = new Step("SELECT l.cardno FROM login l LEFT JOIN signup ", 0, true);
        orphans.columns.put("cardno", "unlinked-card");
        legacy = new Step("SELECT b.id, b.cardno, b.date, b.type, b.amount, ", 0, false);
        resolve = new Step("SELECT a.account_id FROM login l ", 0, false);
        insert = new Step("INSERT INTO transactions ", 1, false);
        count = new Step("SELECT COUNT(*) AS issue_count FROM migration_issues", 0, true);
        count.columns.put("issue_count", "2");
        script = new Script(issues, forms, create, orphans, legacy, resolve, insert, count);
        try (Connection con = script.connection()) { report = MigrateAccounts.migrate(con); }
        check(report.accountsCreated() == 1 && report.transactionsImported() == 0
                && create.typedParameters.get(3).equals(new BigDecimal("0.00"))
                && create.typedParameters.get(4).equals(false), "Create legacy accounts with explicitly unverified placeholder balances");
        check(UUID.fromString(create.parameters.get(0)).version() == 4, "Generate stable UUID account IDs");
        String migratedAccountId = create.parameters.get(0);
        check(script.persisted.stream().anyMatch(row -> row.get(0).equals("accounts")
                && row.get(1).equals(migratedAccountId) && row.get(2).equals("OPENING_BALANCE_UNVERIFIED")),
                "Report the missing authoritative opening balance");
        check(issues.parameters.get(0).equals("login") && issues.parameters.get(1).equals("unlinked-card")
                && issues.parameters.get(2).equals("UNRESOLVED_APPLICATION"), "Report orphaned legacy logins");
        script.verifyClosed();

        issues = new Step("INSERT INTO migration_issues ", 1, false);
        forms = new Step("SELECT DISTINCT l.formno FROM login l ", 0, false);
        create = account();
        orphans = new Step("SELECT l.cardno FROM login l LEFT JOIN signup ", 0, false);
        legacy = new Step("SELECT b.id, b.cardno, b.date, b.type, b.amount, ", 0, true);
        legacy.columns.putAll(Map.of("id", "1", "cardno", "0000000000000001", "date", "2026-10-26T12:00:00Z", "type", "Deposit", "amount", "1.00"));
        resolve = new Step("SELECT a.account_id FROM login l ", 0, true);
        resolve.columns.put("account_id", ACCOUNT_ID);
        insert = new Step("INSERT INTO transactions ", 1, false);
        count = new Step("SELECT COUNT(*) AS issue_count FROM migration_issues", 0, true);
        count.failure = new SQLException("Report query failed after import");
        script = new Script(issues, forms, create, orphans, legacy, resolve, insert, count);
        expectSqlFailure(() -> {
            try (Connection con = script.connection()) { return MigrateAccounts.migrate(con); }
        });
        check(script.commits == 0 && script.rollbacks == 1 && script.persisted.isEmpty() && script.pending.isEmpty(),
                "Roll back imported history when a later migration operation fails");
    }

    private static Registration draft(String senior) {
        Registration registration = new Registration();
        registration.setPersonalDetails(PERSONAL);
        registration.setAdditionalDetails(new AdditionalDetails("Hindu", "General", "1-5 lakhs",
                "Graduate", "Service", "TESTPAN", "000000000000", senior));
        return registration;
    }

    private static void draftAndCompleteness() throws Exception {
        script = new Script();
        Registration registration = draft("No");
        check(registration.getApplicationId().matches("[0-9A-HJKMNP-TV-Z]{20}"), "Use a schema-compatible random application ID");
        check(registration.getPersonalDetails() == PERSONAL, "Keep the details in the shared draft");
        check(script.connections == 0, "Collecting signup details must not open a database connection");
        Registration incomplete = new Registration();
        for (int page = 0; page < 2; page++) {
            try {
                REGISTRATION.submit(incomplete);
                throw new AssertionError("Reject incomplete submissions");
            } catch (IllegalStateException expected) {
                check(script.connections == 0, "Reject incomplete applications before opening JDBC");
            }
            incomplete.setPersonalDetails(PERSONAL);
        }
    }

    private static Step personal() { return new Step("INSERT INTO signup ", 1, false); }
    private static Step additional() { return new Step("INSERT INTO signuptwo(", 1, false); }
    private static Step account() { return new Step("INSERT INTO accounts ", 1, false); }
    private static Step credentials() { return new Step("INSERT INTO login ", 1, false); }

    private static void successfulSubmission() throws Exception {
        for (String senior : new String[]{"Yes", "No"}) {
            Registration registration = draft(senior);
            Step personal = personal(), additional = additional(), credentials = credentials();
            script = new Script(personal, additional, account(), credentials);
            CardCredentials result = REGISTRATION.submit(registration);
            String id = registration.getApplicationId();
            check(personal.parameters.equals(List.of(id, "Test Name", "Test Parent", "Female",
                    "test@example.invalid", "Married", "Test Address", "Test City", "123456", "Test State")),
                    "Preserve personal field mapping");
            check(additional.parameters.equals(List.of(id, "Hindu", "General", "1-5 lakhs", "Graduate",
                    "Service", "TESTPAN", "000000000000", senior)), "Preserve additional field mapping");
            char[] pin = result.getPin();
            try {
                check(result.getCardno().matches("\\d{16}") && PinHasher.isValidPin(pin) && pin[0] != '0', "Preserve card and PIN formats");
                check(credentials.parameters.get(0).equals(result.getCardno()) && credentials.parameters.get(2).equals(id), "Persist the returned card and application ID");
                check(PinHasher.verify(pin, credentials.parameters.get(1)), "Persist a verifiable salted hash of the initial PIN");
                check(!credentials.parameters.get(1).equals(new String(pin)), "Never send the plaintext PIN to JDBC");
            } finally {
                Arrays.fill(pin, '\0');
            }
            check(script.commits == 1 && script.rollbacks == 0 && script.persisted.size() == 4,
                    "Commit all four registration records together");
            check(registration.isSubmitted() && REGISTRATION.submit(registration) == result,
                    "Repeated submission must return the committed credentials without another write");
            try {
                registration.setPersonalDetails(PERSONAL);
                throw new AssertionError("A submitted application must be immutable");
            } catch (IllegalStateException expected) { }
            script.verifyClosed();
        }
    }

    private static void authentication() throws Exception {
        for (int scenario = 0; scenario < 6; scenario++) {
            Step lookup = new Step("SELECT l.formno, s.name, l.pin_hash, a.account_id FROM login l ", 0, scenario != 2);
            lookup.columns.put("account_id", ACCOUNT_ID);
            lookup.columns.put("formno", "1234");
            lookup.columns.put("name", "Test Name");
            lookup.columns.put("pin_hash", switch (scenario) {
                case 3 -> "malformed";
                case 4 -> null;
                case 5 -> "0042";
                default -> PIN_FIXTURE;
            });
            script = new Script(lookup);
            char[] pin = (scenario == 1 ? "0000" : "0042").toCharArray();
            try {
                AuthenticatedUser user = AUTHENTICATION.authenticate("0000000000000001", pin);
                check(lookup.parameters.equals(List.of("0000000000000001")), "Fetch by card number without sending PINs to JDBC");
                check(scenario == 0 ? new AuthenticatedUser(ACCOUNT_ID, "1234", "Test Name").equals(user) : user == null,
                        "Authenticate only a matching supported salted hash; no plaintext fallback");
            } finally {
                Arrays.fill(pin, '\0');
            }
            script.verifyClosed();
        }
        Step lookup = new Step("SELECT l.formno, s.name, l.pin_hash, a.account_id FROM login l ", 0, false);
        lookup.failure = new SQLException("Authentication failed");
        script = new Script(lookup);
        check(expectSqlFailure(() -> AUTHENTICATION.authenticate("0000000000000001", new char[]{'0','0','0','0'})) == lookup.failure,
                "Propagate authentication errors");
    }

    private static Step migrationSelect() {
        return new Step("SELECT cardno, pin, pin_hash FROM login FOR UPDATE", 0, true);
    }

    private static Step migrationUpdate() {
        return new Step("UPDATE login SET pin_hash = ?, pin = NULL WHERE cardno = ?", 1, false);
    }

    private static void pinMigration() throws Exception {
        Step select = migrationSelect(), update = migrationUpdate();
        select.columns.put("cardno", "0000000000000001");
        select.columns.put("pin", "0042");
        script = new Script(select, update);
        try (Connection con = script.connection()) {
            check(MigratePins.migrate(con) == 1, "Upgrade the legacy PIN record");
        }
        check(PinHasher.verify(new char[]{'0','0','4','2'}, update.parameters.get(0)), "Preserve the legacy PIN, including leading zeros");
        check(update.parameters.get(1).equals("0000000000000001") && update.parameters.size() == 2,
                "Update the correct card without sending the legacy PIN back to JDBC");
        check(script.commits == 1 && script.rollbacks == 0, "Commit converted records together");
        script.verifyClosed();

        select = migrationSelect();
        select.columns.put("pin_hash", PIN_FIXTURE);
        script = new Script(select, migrationUpdate());
        try (Connection con = script.connection()) {
            check(MigratePins.migrate(con) == 0, "Reruns skip already upgraded records");
        }
        check(script.pending.isEmpty() && script.persisted.isEmpty(), "Do not overwrite hashes on rerun");
        script.verifyClosed();

        for (String invalid : new String[]{"bad", "12345"}) {
            select = migrationSelect();
            select.columns.put("pin", invalid);
            script = new Script(select, migrationUpdate());
            expectSqlFailure(() -> {
                try (Connection con = script.connection()) { return MigratePins.migrate(con); }
            });
            check(script.rollbacks == 1 && script.commits == 0, "Invalid legacy credentials must roll back the migration");
        }
        select = migrationSelect();
        select.columns.put("pin", "0042");
        update = migrationUpdate();
        update.failure = new SQLException("Migration update failed");
        script = new Script(select, update);
        expectSqlFailure(() -> {
            try (Connection con = script.connection()) { return MigratePins.migrate(con); }
        });
        check(script.rollbacks == 1 && script.commits == 0, "Roll back failed credential updates");

        select = migrationSelect();
        select.rows = List.of(
                Map.of("cardno", "0000000000000001", "pin", "0042"),
                Map.of("cardno", "0000000000000002", "pin", "bad"));
        script = new Script(select, migrationUpdate());
        expectSqlFailure(() -> {
            try (Connection con = script.connection()) { return MigratePins.migrate(con); }
        });
        check(script.commits == 0 && script.rollbacks == 1 && script.pending.isEmpty() && script.persisted.isEmpty(),
                "A later invalid row must undo earlier converted rows in the same migration");
    }

    private static void migrationLogSafety() throws Exception {
        String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                DaoTest.class.getName(), "--migration-failure-probe").redirectErrorStream(true).start();
        check(child.waitFor(10, TimeUnit.SECONDS), "Migration log probe completed");
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(child.exitValue() == 1 && output.contains("PIN migration failed") && !output.contains("6789"),
                "Do not print driver exception messages containing PINs");
    }

    private static void rollbackFailures() throws Exception {
        for (int failedStage = 0; failedStage < 4; failedStage++) {
            for (boolean zeroRows : new boolean[]{false, true}) {
                Step[] steps = {personal(), additional(), account(), credentials()};
                if (zeroRows) steps[failedStage].updateCount = 0;
                else steps[failedStage].failure = new SQLException("Foreign key or database error", "23000", 1452);
                script = new Script(Arrays.copyOf(steps, failedStage + 1));
                Registration registration = draft("No");
                SQLException error = expectSqlFailure(() -> REGISTRATION.submit(registration));
                if (!zeroRows) check(error == steps[failedStage].failure, "Preserve the original SQL error");
                check(script.commits == 0 && script.rollbacks == 1 && script.persisted.isEmpty() && script.pending.isEmpty(),
                        "Failure at any insert must roll back all pending records without retrying");
                check(!registration.isSubmitted() && !registration.isOutcomeUnknown(), "Allow retry after confirmed rollback");
            }
        }
        Step duplicateAdditional = additional();
        duplicateAdditional.failure = duplicateKey();
        script = new Script(personal(), duplicateAdditional);
        expectSqlFailure(() -> REGISTRATION.submit(draft("No")));
        check(script.rollbacks == 1, "Do not treat unrelated unique constraints as generated-ID collisions");
    }

    private static void retryAfterRollback() throws Exception {
        Registration registration = draft("No");
        String id = registration.getApplicationId();
        Step fail = additional();
        fail.failure = new SQLException("Temporary insert failure");
        script = new Script(personal(), fail);
        expectSqlFailure(() -> REGISTRATION.submit(registration));
        script = new Script(personal(), additional(), account(), credentials());
        REGISTRATION.submit(registration);
        check(registration.isSubmitted() && registration.getApplicationId().equals(id),
                "A confirmed rollback must allow resubmission of the same collected application");
        check(script.persisted.size() == 4 && script.commits == 1, "A later successful submission persists exactly one account");
        script.verifyClosed();
    }

    private static SQLException duplicateKey() { return new SQLException("Duplicate entry", "23000", 1062); }

    private static void collisionRetries() throws Exception {
        for (String kind : new String[]{"application", "account", "card"}) {
            Registration registration = draft("No");
            String originalId = registration.getApplicationId();
            String originalAccount = registration.getAccountId();
            Step failed = collisionStep(kind);
            failed.failure = duplicateKey();
            Step personal = personal(), additional = additional(), account = account(), credentials = credentials();
            List<Step> steps = collisionPrefix(kind);
            steps.add(failed);
            steps.addAll(List.of(personal, additional, account, credentials));
            script = new Script(steps.toArray(Step[]::new));
            REGISTRATION.submit(registration);
            check(kind.equals("application") != originalId.equals(registration.getApplicationId()),
                    "Only regenerate the application ID for an application-ID collision");
            check(kind.equals("account") != originalAccount.equals(registration.getAccountId()),
                    "Keep the account ID stable except for a pre-commit account-ID collision");
            check(personal.parameters.get(0).equals(additional.parameters.get(0))
                    && additional.parameters.get(0).equals(credentials.parameters.get(2))
                    && account.parameters.get(1).equals(personal.parameters.get(0)), "Keep all application references consistent");
            check(account.parameters.get(0).equals(registration.getAccountId())
                    && account.typedParameters.get(3).equals(new BigDecimal("0.00"))
                    && account.typedParameters.get(4).equals(true), "Create an account with an exact verified zero balance");
            check(script.rollbacks == 1 && script.commits == 1 && script.persisted.size() == 4,
                    "Retry collisions only after rolling back the entire failed attempt");
            script.verifyClosed();

            List<Step> attempts = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                attempts.addAll(collisionPrefix(kind));
                Step collision = collisionStep(kind);
                collision.failure = duplicateKey();
                attempts.add(collision);
            }
            script = new Script(attempts.toArray(Step[]::new));
            SQLException error = expectSqlFailure(() -> REGISTRATION.submit(draft("Yes")));
            check(error.getMessage().contains("5 attempts"), "Report the exhausted collision retry limit");
            check(script.commits == 0 && script.rollbacks == 5 && script.persisted.isEmpty() && script.pending.isEmpty(),
                    "Bound all generated-ID collisions to five attempts without partial records");
        }
    }

    private static Step collisionStep(String kind) {
        return switch (kind) { case "application" -> personal(); case "account" -> account(); default -> credentials(); };
    }

    private static List<Step> collisionPrefix(String kind) {
        List<Step> prefix = new ArrayList<>();
        if (!kind.equals("application")) { prefix.add(personal()); prefix.add(additional()); }
        if (kind.equals("card")) prefix.add(account());
        return prefix;
    }

    private static void concurrentSubmission() throws Exception {
        Registration registration = draft("No");
        script = new Script(personal(), additional(), account(), credentials());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<CardCredentials> submit = () -> { start.await(); return new RegistrationDao().submit(registration); };
            Future<CardCredentials> first = pool.submit(submit), second = pool.submit(submit);
            start.countDown();
            check(first.get(10, TimeUnit.SECONDS) == second.get(10, TimeUnit.SECONDS), "Concurrent callers receive the same credentials");
        }
        check(script.commits == 1 && script.persisted.size() == 4, "Concurrent submissions must not duplicate registration");
        script.verifyClosed();
    }

    private static void uncertainOutcomes() throws Exception {
        for (boolean commitAcknowledgementLost : new boolean[]{false, true}) {
            Registration registration = draft("No");
            script = new Script(personal(), additional(), account(), credentials());
            script.commitFailure = new SQLException("Commit connection lost");
            script.commitBeforeFailure = commitAcknowledgementLost;
            expectSqlFailure(() -> REGISTRATION.submit(registration));
            check(registration.isOutcomeUnknown() && !registration.isSubmitted(), "Do not claim an unconfirmed commit succeeded");
            expectSqlFailure(() -> REGISTRATION.submit(registration));
            check(script.commits == 1 && script.rollbacks == 1, "Do not resubmit an application after an uncertain commit");
        }
        Registration rollbackRegistration = draft("No");
        Step fail = additional();
        fail.failure = new SQLException("Insert failed");
        script = new Script(personal(), fail);
        script.rollbackFailure = new SQLException("Rollback connection lost");
        SQLException error = expectSqlFailure(() -> REGISTRATION.submit(rollbackRegistration));
        check(rollbackRegistration.isOutcomeUnknown() && error.getCause().getSuppressed()[0] == script.rollbackFailure,
                "Preserve rollback failures and block unsafe resubmission");
        expectSqlFailure(() -> REGISTRATION.submit(rollbackRegistration));

        Registration committedRegistration = draft("No");
        script = new Script(personal(), additional(), account(), credentials());
        script.closeFailure = new SQLException("Close failed after commit");
        CardCredentials result = REGISTRATION.submit(committedRegistration);
        check(result == committedRegistration.getCredentials() && committedRegistration.isSubmitted(), "Cleanup errors must not invalidate a successful commit");
        script.verifyClosed();
    }

    private static SQLException expectSqlFailure(Callable<?> action) throws Exception {
        try {
            action.call();
            throw new AssertionError("Expected SQLException");
        } catch (SQLException error) {
            script.verifyClosed();
            return error;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class Script {
        private final List<Step> steps;
        private final Deque<Step> remaining;
        private int connections;
        private int closedConnections;
        private boolean autoCommit = true;
        private int commits;
        private int rollbacks;
        private final List<List<String>> pending = new ArrayList<>();
        private final List<List<String>> persisted = new ArrayList<>();
        private SQLException commitFailure;
        private SQLException rollbackFailure;
        private SQLException closeFailure;
        private boolean commitBeforeFailure;

        private Script(Step... steps) {
            this.steps = Arrays.asList(steps);
            remaining = new ArrayDeque<>(this.steps);
        }

        private Connection connection() {
            connections++;
            return proxy(Connection.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "prepareStatement":
                        check(!remaining.isEmpty(), "Unexpected database operation");
                        Step step = remaining.removeFirst();
                        step.actualSql = (String) args[0];
                        check(step.actualSql.startsWith(step.sqlPrefix), "Unexpected SQL: " + args[0]);
                        return step.statement();
                    case "setAutoCommit":
                        autoCommit = (boolean) args[0];
                        check(!autoCommit, "Use an explicit transaction");
                        return null;
                    case "commit":
                        commits++;
                        if (commitFailure == null || commitBeforeFailure) {
                            persisted.addAll(pending);
                            pending.clear();
                        }
                        if (commitFailure != null) throw commitFailure;
                        return null;
                    case "rollback":
                        rollbacks++;
                        if (rollbackFailure != null) throw rollbackFailure;
                        pending.clear();
                        return null;
                    case "close":
                        closedConnections++;
                        if (closeFailure != null) throw closeFailure;
                        return null;
                    default: throw new AssertionError("Unexpected Connection method: " + method.getName());
                }
            });
        }

        private void verifyClosed() {
            check(remaining.isEmpty(), "Expected database operations were skipped");
            check(connections == 1 && closedConnections == 1, "Close the operation's connection exactly once");
            for (Step step : steps) {
                check(step.closedStatements == 1, "Close each statement exactly once");
                check(step.openedResults == step.closedResults, "Close every result set");
            }
        }
    }

    private static final class Step {
        private final String sqlPrefix;
        private String actualSql;
        private int updateCount;
        private final boolean hasRow;
        private final Map<String, String> columns = new HashMap<>();
        private List<Map<String, String>> rows;
        private final List<String> parameters = new ArrayList<>();
        private final Map<Integer, Object> typedParameters = new HashMap<>();
        private SQLException failure;
        private int closedStatements;
        private int openedResults;
        private int closedResults;

        private Step(String sqlPrefix, int updateCount, boolean hasRow) {
            this.sqlPrefix = sqlPrefix;
            this.updateCount = updateCount;
            this.hasRow = hasRow;
        }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "setString", "setBigDecimal", "setBoolean", "setLong", "setObject":
                        int index = (int) args[0] - 1;
                        while (parameters.size() <= index) parameters.add(null);
                        parameters.set(index, args[1] == null ? null : args[1].toString());
                        typedParameters.put((int) args[0], args[1]);
                        return null;
                    case "executeUpdate":
                        check(!script.autoCommit, "Every registration write must be inside the transaction");
                        if (failure != null) throw failure;
                        if (updateCount == 1) script.pending.add(List.copyOf(parameters));
                        return updateCount;
                    case "executeQuery":
                        if (failure != null) throw failure;
                        return resultSet();
                    case "close": closedStatements++; return null;
                    default: throw new AssertionError("Unexpected statement method: " + method.getName());
                }
            });
        }

        private ResultSet resultSet() {
            openedResults++;
            int[] rowIndex = {-1};
            return proxy(ResultSet.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        rowIndex[0]++;
                        return hasRow && rowIndex[0] < (rows == null ? 1 : rows.size());
                    case "getString": return (rows == null ? columns : rows.get(rowIndex[0])).get((String) args[0]);
                    case "getLong": return Long.parseLong((rows == null ? columns : rows.get(rowIndex[0])).get((String) args[0]));
                    case "getBoolean": return Boolean.parseBoolean((rows == null ? columns : rows.get(rowIndex[0])).get((String) args[0]));
                    case "getBigDecimal": return new BigDecimal((rows == null ? columns : rows.get(rowIndex[0])).get((String) args[0]));
                    case "getObject": return LocalDateTime.parse((rows == null ? columns : rows.get(rowIndex[0])).get((String) args[0]));
                    case "getCharacterStream":
                        String value = (rows == null ? columns : rows.get(rowIndex[0])).get((String) args[0]);
                        return value == null ? null : new StringReader(value);
                    case "close": closedResults++; return null;
                    default: throw new AssertionError("Unexpected result set method: " + method.getName());
                }
            });
        }
    }

    private static final class TestDriver implements Driver {
        public Connection connect(String url, Properties info) { return acceptsURL(url) ? script.connection() : null; }
        public boolean acceptsURL(String url) { return TEST_URL.equals(url); }
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        public int getMajorVersion() { return 1; }
        public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
    }
}
