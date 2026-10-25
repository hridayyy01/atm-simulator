package atm.dao;

import atm.model.AdditionalDetails;
import atm.model.AuthenticatedUser;
import atm.model.CardCredentials;
import atm.model.Registration;
import atm.model.PersonalDetails;

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
    private static Script script;

    public static void main(String[] args) throws Exception {
        check(TEST_URL.equals(System.getenv("DB_URL")), "Use the test JDBC URL from README.md");
        Driver driver = new TestDriver();
        DriverManager.registerDriver(driver);
        try {
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
    private static Step credentials() { return new Step("INSERT INTO login ", 1, false); }

    private static void successfulSubmission() throws Exception {
        for (String senior : new String[]{"Yes", "No"}) {
            Registration registration = draft(senior);
            Step personal = personal(), additional = additional(), credentials = credentials();
            script = new Script(personal, additional, credentials);
            CardCredentials result = REGISTRATION.submit(registration);
            String id = registration.getApplicationId();
            check(personal.parameters.equals(List.of(id, "Test Name", "Test Parent", "Female",
                    "test@example.invalid", "Married", "Test Address", "Test City", "123456", "Test State")),
                    "Preserve personal field mapping");
            check(additional.parameters.equals(List.of(id, "Hindu", "General", "1-5 lakhs", "Graduate",
                    "Service", "TESTPAN", "000000000000", senior)), "Preserve additional field mapping");
            check(result.getCardno().matches("\\d{16}") && result.getPin().matches("[1-9]\\d{3}"), "Preserve card and PIN formats");
            check(credentials.parameters.equals(List.of(result.getCardno(), result.getPin(), id)), "Persist the returned credentials");
            check(script.commits == 1 && script.rollbacks == 0 && script.persisted.size() == 3,
                    "Commit all three registration records together");
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
        for (boolean matches : new boolean[]{true, false}) {
            Step lookup = new Step("SELECT l.formno, s.name FROM login l ", 0, matches);
            lookup.columns.put("formno", "1234");
            lookup.columns.put("name", "Test Name");
            script = new Script(lookup);
            AuthenticatedUser user = AUTHENTICATION.authenticate("0000000000000001", "0000");
            check(lookup.parameters.equals(List.of("0000000000000001", "0000")), "Preserve leading zeros");
            check(matches ? new AuthenticatedUser("1234", "Test Name").equals(user) : user == null,
                    "Only return a user when credentials match");
            script.verifyClosed();
        }
        Step lookup = new Step("SELECT l.formno, s.name FROM login l ", 0, false);
        lookup.failure = new SQLException("Authentication failed");
        script = new Script(lookup);
        check(expectSqlFailure(() -> AUTHENTICATION.authenticate("0000000000000001", "0000")) == lookup.failure,
                "Propagate authentication errors");
    }

    private static void rollbackFailures() throws Exception {
        for (int failedStage = 0; failedStage < 3; failedStage++) {
            for (boolean zeroRows : new boolean[]{false, true}) {
                Step[] steps = {personal(), additional(), credentials()};
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
        script = new Script(personal(), additional(), credentials());
        REGISTRATION.submit(registration);
        check(registration.isSubmitted() && registration.getApplicationId().equals(id),
                "A confirmed rollback must allow resubmission of the same collected application");
        check(script.persisted.size() == 3 && script.commits == 1, "A later successful submission persists exactly one account");
        script.verifyClosed();
    }

    private static SQLException duplicateKey() { return new SQLException("Duplicate entry", "23000", 1062); }

    private static void collisionRetries() throws Exception {
        for (boolean applicationCollision : new boolean[]{true, false}) {
            Registration registration = draft("No");
            String originalId = registration.getApplicationId();
            Step failed = applicationCollision ? personal() : credentials();
            failed.failure = duplicateKey();
            Step personal = personal(), additional = additional(), credentials = credentials();
            script = applicationCollision ? new Script(failed, personal, additional, credentials)
                    : new Script(personal(), additional(), failed, personal, additional, credentials);
            REGISTRATION.submit(registration);
            check(applicationCollision != originalId.equals(registration.getApplicationId()),
                    "Only regenerate the application ID for an application-ID collision");
            check(personal.parameters.get(0).equals(additional.parameters.get(0))
                    && additional.parameters.get(0).equals(credentials.parameters.get(2)), "Use the same ID in all records after retry");
            check(script.rollbacks == 1 && script.commits == 1 && script.persisted.size() == 3,
                    "Retry collisions only after rolling back the entire failed attempt");
            script.verifyClosed();

            List<Step> attempts = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                if (!applicationCollision) { attempts.add(personal()); attempts.add(additional()); }
                Step collision = applicationCollision ? personal() : credentials();
                collision.failure = duplicateKey();
                attempts.add(collision);
            }
            script = new Script(attempts.toArray(Step[]::new));
            SQLException error = expectSqlFailure(() -> REGISTRATION.submit(draft("Yes")));
            check(error.getMessage().contains("5 attempts"), "Report the exhausted collision retry limit");
            check(script.commits == 0 && script.rollbacks == 5 && script.persisted.isEmpty() && script.pending.isEmpty(),
                    "Bound both application-ID and card collisions to five attempts without partial records");
        }
    }

    private static void concurrentSubmission() throws Exception {
        Registration registration = draft("No");
        script = new Script(personal(), additional(), credentials());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<CardCredentials> submit = () -> { start.await(); return new RegistrationDao().submit(registration); };
            Future<CardCredentials> first = pool.submit(submit), second = pool.submit(submit);
            start.countDown();
            check(first.get(10, TimeUnit.SECONDS) == second.get(10, TimeUnit.SECONDS), "Concurrent callers receive the same credentials");
        }
        check(script.commits == 1 && script.persisted.size() == 3, "Concurrent submissions must not duplicate registration");
        script.verifyClosed();
    }

    private static void uncertainOutcomes() throws Exception {
        for (boolean commitAcknowledgementLost : new boolean[]{false, true}) {
            Registration registration = draft("No");
            script = new Script(personal(), additional(), credentials());
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
        script = new Script(personal(), additional(), credentials());
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
                        check(((String) args[0]).startsWith(step.sqlPrefix), "Unexpected SQL: " + args[0]);
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
        private int updateCount;
        private final boolean hasRow;
        private final Map<String, String> columns = new HashMap<>();
        private final List<String> parameters = new ArrayList<>();
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
                    case "setString":
                        int index = (int) args[0] - 1;
                        while (parameters.size() <= index) parameters.add(null);
                        parameters.set(index, (String) args[1]);
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
            boolean[] visited = {false};
            return proxy(ResultSet.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        boolean found = hasRow && !visited[0];
                        visited[0] = true;
                        return found;
                    case "getString": return columns.get((String) args[0]);
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
