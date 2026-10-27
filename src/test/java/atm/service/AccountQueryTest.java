package atm.service;

import atm.dao.AccountDao;
import atm.dao.TransactionDao;
import atm.model.Account;
import atm.model.AuthenticatedUser;
import atm.model.Session;
import atm.model.Transaction;
import atm.model.TransactionType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/** Read-boundary regression tests using a JDBC model, without a MySQL server. */
public final class AccountQueryTest {
    private static final String URL = "jdbc:atm-query-test";
    private static final String A = "11111111-1111-4111-8111-111111111111";
    private static final String B = "22222222-2222-4222-8222-222222222222";
    private static final String EMPTY = "33333333-3333-4333-8333-333333333333";
    private static final AccountDao ACCOUNTS = new AccountDao();
    private static final TransactionDao TRANSACTIONS = new TransactionDao();
    private static final AccountService SERVICE = new AccountService(ACCOUNTS, TRANSACTIONS);
    private static final List<Account> ACCOUNTS_DATA = List.of(
            new Account(A, "application-A", new BigDecimal("9007199254740993.01"), true),
            new Account(B, "application-B", new BigDecimal("42.50"), true),
            new Account(EMPTY, "application-empty", new BigDecimal("0.00"), true));
    private static final List<Transaction> HISTORY = history();
    private static int connections, closedConnections, statements, closedStatements, results, closedResults;

    public static void main(String[] args) throws Exception {
        check(URL.equals(System.getenv("DB_URL")), "Use the isolated query-test JDBC URL from README.md");
        Driver driver = new TestDriver();
        DriverManager.registerDriver(driver);
        try {
            balances();
            statementOrderAndIsolation();
            emptyHistory();
            mismatchedOwnership();
            expiredSessions();
            verifyClosed();
            System.out.println("PASS: session-scoped balances/history, timestamp-ID ordering, ten-row limit, empty history and query cleanup (JDBC model).");
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    private static Session session(String account, String form) {
        return new Session(new AuthenticatedUser(account, form, "Test User"));
    }

    private static void balances() throws Exception {
        Account first = SERVICE.getAccount(session(A, "application-A"));
        Account second = SERVICE.getAccount(session(B, "application-B"));
        check(first.accountId().equals(A) && first.balance().equals(new BigDecimal("9007199254740993.01")),
                "Read the current user's exact balance, including values beyond floating-point precision");
        check(second.accountId().equals(B) && second.balance().equals(new BigDecimal("42.50")),
                "A separate session must read its own account");
        verifyClosed();
    }

    private static void statementOrderAndIsolation() throws Exception {
        List<Transaction> recent = SERVICE.getRecentTransactions(session(A, "application-A"));
        check(recent.stream().map(Transaction::transactionId).toList()
                .equals(List.of(109L, 107L, 102L, 111L, 106L, 104L, 108L, 103L, 101L, 110L)),
                "Order by timestamp descending, then numeric transaction ID descending, before limiting to ten");
        check(recent.stream().allMatch(t -> t.accountId().equals(A)), "Exclude newer foreign-account rows");
        List<Transaction> other = SERVICE.getRecentTransactions(session(B, "application-B"));
        check(other.stream().map(Transaction::transactionId).toList().equals(List.of(1000L, 999L)),
                "The other user's statement must remain independent");
        List<Transaction> complete = TRANSACTIONS.findForSession(session(A, "application-A"));
        check(complete.size() == 12 && complete.get(0).transactionId() == 100L
                && complete.get(1).transactionId() == 105L && complete.get(2).transactionId() == 110L,
                "The full-history query must also enforce ownership and timestamp-ID order");
        check(complete.stream().allMatch(t -> t.accountId().equals(A)), "Scope full history as well as mini statements");
        verifyClosed();
    }

    private static void emptyHistory() throws Exception {
        Session empty = session(EMPTY, "application-empty");
        check(SERVICE.getAccount(empty).balance().equals(new BigDecimal("0.00")), "Support a valid zero-balance account");
        check(SERVICE.getRecentTransactions(empty).isEmpty(), "Return an explicit empty history for an owned account without transactions");
        verifyClosed();
    }

    private static void mismatchedOwnership() throws Exception {
        for (Session mismatch : List.of(session(B, "application-A"), session(A, "application-B"),
                session("missing-account", "application-A"), session("' OR 1=1 --", "application-A"))) {
            check(ACCOUNTS.findForSession(mismatch) == null, "An account-ID/application mismatch must not expose a balance");
            check(TRANSACTIONS.findRecentForSession(mismatch).isEmpty(), "The DAO must not expose foreign history");
            check(TRANSACTIONS.findForSession(mismatch).isEmpty(), "The full-history DAO must not expose foreign history");
            denied(() -> SERVICE.getAccount(mismatch));
            denied(() -> SERVICE.getRecentTransactions(mismatch));
        }
        verifyClosed();
    }

    private static void denied(Callable<?> operation) throws Exception {
        try {
            operation.call();
            throw new AssertionError("Reject a non-owned account");
        } catch (AccountOperationException expected) {
            check(expected.getReason() == AccountOperationException.Reason.ACCOUNT_NOT_FOUND,
                    "Do not distinguish foreign accounts from missing accounts");
        }
    }

    private static void expiredSessions() throws Exception {
        Session expired = session(A, "application-A");
        expired.logout();
        int before = connections;
        List<Callable<?>> operations = List.of(() -> SERVICE.getAccount(expired),
                () -> SERVICE.getRecentTransactions(expired), () -> ACCOUNTS.findForSession(expired),
                () -> TRANSACTIONS.findRecentForSession(expired), () -> TRANSACTIONS.findForSession(expired));
        for (Callable<?> operation : operations) {
            try { operation.call(); throw new AssertionError("Reject an expired session"); }
            catch (IllegalStateException expected) { }
        }
        check(connections == before, "Expired sessions must be rejected before opening JDBC");
    }

    private static List<Transaction> history() {
        Instant base = Instant.parse("2026-10-27T08:00:00.123456Z");
        List<Transaction> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rows.add(new Transaction(100 + (i * 5) % 12, A,
                    i % 2 == 0 ? TransactionType.DEPOSIT : TransactionType.WITHDRAWAL,
                    new BigDecimal("0.10").add(BigDecimal.valueOf(i)), base.plusSeconds(i / 3)));
        }
        rows.add(new Transaction(999, B, TransactionType.DEPOSIT, new BigDecimal("42.50"), base.plusSeconds(100)));
        rows.add(new Transaction(1000, B, TransactionType.WITHDRAWAL, new BigDecimal("1.00"), base.plusSeconds(100)));
        return List.copyOf(rows);
    }

    private static Connection connection() {
        connections++;
        boolean[] closed = {false};
        return proxy(Connection.class, (p, method, args) -> {
            return switch (method.getName()) {
                case "prepareStatement" -> statement((String) args[0]);
                case "close" -> {
                    check(!closed[0], "Close each connection once");
                    closed[0] = true; closedConnections++; yield null;
                }
                default -> throw new AssertionError("Unexpected connection call: " + method.getName());
            };
        });
    }

    private static PreparedStatement statement(String sql) {
        statements++;
        Map<Integer, String> parameters = new HashMap<>();
        boolean[] closed = {false};
        return proxy(PreparedStatement.class, (p, method, args) -> {
            return switch (method.getName()) {
                case "setString" -> { parameters.put((int) args[0], (String) args[1]); yield null; }
                case "executeQuery" -> query(sql, parameters);
                case "close" -> {
                    check(!closed[0], "Close each statement once");
                    closed[0] = true; closedStatements++; yield null;
                }
                default -> throw new AssertionError("Unexpected statement call: " + method.getName());
            };
        });
    }

    private static ResultSet query(String sql, Map<Integer, String> parameters) {
        check(parameters.size() == 2 && parameters.get(1) != null && parameters.get(2) != null,
                "Bind account ID and application identity from the session");
        Account owned = ACCOUNTS_DATA.stream().filter(a -> a.accountId().equals(parameters.get(1))
                && a.formno().equals(parameters.get(2))).findFirst().orElse(null);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (sql.startsWith("SELECT account_id, formno, balance, balance_verified FROM accounts ")) {
            check(sql.endsWith("WHERE account_id = ? AND formno = ?"), "Enforce balance ownership in SQL");
            if (owned != null) rows.add(Map.of("account_id", owned.accountId(), "formno", owned.formno(),
                    "balance", owned.balance(), "balance_verified", owned.balanceVerified()));
        } else {
            check(sql.startsWith("SELECT t.transaction_id, t.account_id, t.transaction_type, t.amount, t.occurred_at ")
                    && sql.contains("JOIN accounts a ON a.account_id = t.account_id ")
                    && sql.contains("WHERE t.account_id = ? AND a.formno = ? "), "Enforce history ownership in SQL");
            boolean recent = sql.endsWith("ORDER BY t.occurred_at DESC, t.transaction_id DESC LIMIT 10");
            check(recent || sql.endsWith("ORDER BY t.occurred_at, t.transaction_id"), "Use deterministic timestamp/ID ordering");
            Comparator<Transaction> order = Comparator.comparing(Transaction::occurredAt).thenComparingLong(Transaction::transactionId);
            if (recent) order = order.reversed();
            if (owned != null) {
                HISTORY.stream().filter(t -> t.accountId().equals(owned.accountId())).sorted(order)
                        .limit(recent ? 10 : Long.MAX_VALUE).forEach(t -> rows.add(Map.of("transaction_id", t.transactionId(),
                                "account_id", t.accountId(), "transaction_type", t.type().name(), "amount", t.amount(),
                                "occurred_at", LocalDateTime.ofInstant(t.occurredAt(), ZoneOffset.UTC))));
            }
        }
        return resultSet(rows);
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        results++;
        int[] index = {-1};
        boolean[] closed = {false};
        return proxy(ResultSet.class, (p, method, args) -> {
            return switch (method.getName()) {
                case "next" -> ++index[0] < rows.size();
                case "getString" -> rows.get(index[0]).get((String) args[0]).toString();
                case "getBigDecimal", "getBoolean", "getLong", "getObject" -> rows.get(index[0]).get((String) args[0]);
                case "close" -> {
                    check(!closed[0], "Close each result set once");
                    closed[0] = true; closedResults++; yield null;
                }
                default -> throw new AssertionError("Unexpected result call: " + method.getName());
            };
        });
    }

    private static void verifyClosed() {
        check(connections == closedConnections && statements == closedStatements && results == closedResults,
                "Close every JDBC resource after all read outcomes");
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestDriver implements Driver {
        public Connection connect(String url, Properties info) { return acceptsURL(url) ? connection() : null; }
        public boolean acceptsURL(String url) { return URL.equals(url); }
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        public int getMajorVersion() { return 1; }
        public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
    }
}
