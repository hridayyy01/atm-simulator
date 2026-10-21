package atm.dao;

import atm.model.AdditionalDetails;
import atm.model.AuthenticatedUser;
import atm.model.CardCredentials;
import atm.model.CardIssueResult;
import atm.model.PersonalDetails;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/** Headless tests using a scripted JDBC driver, without a database or Swing. */
public final class DaoTest {
    private static final String TEST_URL = "jdbc:atm-dao-test";
    private static final RegistrationDao REGISTRATION = new RegistrationDao();
    private static final AuthenticationDao AUTHENTICATION = new AuthenticationDao();
    private static final PersonalDetails PERSONAL = new PersonalDetails("1234", "Test Name",
            "Test Parent", "Female", "test@example.invalid", "Married", "Test Address",
            "Test City", "123456", "Test State");
    private static Script script;

    public static void main(String[] args) throws Exception {
        check(TEST_URL.equals(System.getenv("DB_URL")), "Use the test JDBC URL from README.md");
        Driver driver = new TestDriver();
        DriverManager.registerDriver(driver);
        try {
            registrationWrites();
            authentication();
            cardIssuance();
            failures();
            System.out.println("PASS: DAO data mapping, outcomes, duplicate-card retries, SQL errors and cleanup.");
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    private static void registrationWrites() throws Exception {
        for (int count : new int[]{1, 0}) {
            Step insert = new Step("INSERT INTO signup ", count, false);
            script = new Script(insert);
            check(REGISTRATION.savePersonalDetails(PERSONAL) == count, "Preserve the personal insert count");
            check(insert.parameters.equals(List.of("1234", "Test Name", "Test Parent", "Female",
                    "test@example.invalid", "Married", "Test Address", "Test City", "123456", "Test State")),
                    "Personal details must map to the matching columns");
            script.verifyClosed();
        }
        for (String senior : new String[]{"Yes", "No"}) {
            Step insert = new Step("INSERT INTO signuptwo(", 1, false);
            script = new Script(insert);
            REGISTRATION.saveAdditionalDetails(additional(senior));
            check(insert.parameters.equals(List.of("1234", "Hindu", "General", "1-5 lakhs",
                    "Graduate", "Service", "TESTPAN", "000000000000", senior)),
                    "Additional details must map to the matching columns, including senior status");
            script.verifyClosed();
        }
    }

    private static AdditionalDetails additional(String senior) {
        return new AdditionalDetails("1234", "Hindu", "General", "1-5 lakhs", "Graduate",
                "Service", "TESTPAN", "000000000000", senior);
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
    }

    private static void cardIssuance() throws Exception {
        Step ineligible = eligibility(false);
        script = new Script(ineligible);
        CardIssueResult result = REGISTRATION.issueCard("1234");
        check(result.status() == CardIssueResult.Status.INCOMPLETE_OR_ALREADY_ISSUED && result.credentials() == null,
                "Do not issue cards for incomplete registrations or already-issued forms");
        check(ineligible.parameters.equals(List.of("1234")), "Check the requested form");
        script.verifyClosed();

        for (int count : new int[]{1, 0}) {
            Step available = duplicate(false);
            Step insert = new Step("INSERT INTO login ", count, false);
            script = new Script(eligibility(true), duplicate(true), available, insert);
            result = REGISTRATION.issueCard("1234");
            String cardno = insert.parameters.get(0);
            String pin = insert.parameters.get(1);
            check(cardno.matches("\\d{16}"), "Generate a 16-digit card number");
            check(pin.matches("[1-9]\\d{3}"), "Preserve the existing PIN range");
            check(insert.parameters.get(2).equals("1234"), "Issue credentials for the requested form");
            check(available.parameters.equals(List.of(cardno)), "Insert the card that passed the uniqueness check");
            if (count == 1) {
                check(result.status() == CardIssueResult.Status.ISSUED, "Report successful issuance");
                CardCredentials credentials = result.credentials();
                check(credentials.getCardno().equals(cardno) && credentials.getPin().equals(pin),
                        "Return exactly the inserted credentials");
            } else {
                check(result.status() == CardIssueResult.Status.INSERT_FAILED && result.credentials() == null,
                        "A zero-row insert must not report an issued card");
            }
            script.verifyClosed();
        }
    }

    private static void failures() throws Exception {
        Step personal = failing("INSERT INTO signup ");
        script = new Script(personal);
        expectFailure(() -> REGISTRATION.savePersonalDetails(PERSONAL), personal.failure);
        Step additional = failing("INSERT INTO signuptwo(");
        script = new Script(additional);
        expectFailure(() -> { REGISTRATION.saveAdditionalDetails(additional("No")); return null; }, additional.failure);
        Step authentication = failing("SELECT l.formno, s.name FROM login l ");
        script = new Script(authentication);
        expectFailure(() -> AUTHENTICATION.authenticate("0000000000000001", "0000"), authentication.failure);
        Step eligibility = failing("SELECT s.formno FROM signup s ");
        script = new Script(eligibility);
        expectFailure(() -> REGISTRATION.issueCard("1234"), eligibility.failure);
        Step duplicate = failing("SELECT cardno FROM login WHERE cardno = ?");
        script = new Script(eligibility(true), duplicate);
        expectFailure(() -> REGISTRATION.issueCard("1234"), duplicate.failure);
        Step insert = failing("INSERT INTO login ");
        script = new Script(eligibility(true), duplicate(false), insert);
        expectFailure(() -> REGISTRATION.issueCard("1234"), insert.failure);
    }

    private static Step eligibility(boolean eligible) { return new Step("SELECT s.formno FROM signup s ", 0, eligible); }
    private static Step duplicate(boolean exists) { return new Step("SELECT cardno FROM login WHERE cardno = ?", 0, exists); }

    private static Step failing(String prefix) {
        Step step = new Step(prefix, 0, false);
        step.failure = new SQLException("Expected test database error", "TEST", 123);
        return step;
    }

    private static void expectFailure(Callable<?> action, SQLException expected) throws Exception {
        try {
            action.call();
            throw new AssertionError("Expected SQLException");
        } catch (SQLException actual) {
            check(actual == expected, "Propagate the original SQLException to the UI");
        }
        script.verifyClosed();
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
                    case "close": closedConnections++; return null;
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
        private final int updateCount;
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
                        if (failure != null) throw failure;
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
