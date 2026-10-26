package atm.service;

import atm.dao.AccountDao;
import atm.dao.TransactionDao;
import atm.model.Account;
import atm.model.AuthenticatedUser;
import atm.model.Session;
import atm.model.TransactionType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/** Exercises real service/DAO code against a transactional, row-locking JDBC test driver. */
public final class AccountServiceTest {
    private static final String URL = "jdbc:atm-service-test";
    private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";
    private static final AccountService SERVICE = new AccountService(new AccountDao(), new TransactionDao());
    private static Database database;

    public static void main(String[] args) throws Exception {
        check(URL.equals(System.getenv("DB_URL")), "Use the isolated service-test JDBC URL from README.md");
        Driver driver = new TestDriver();
        DriverManager.registerDriver(driver);
        try {
            validation();
            competingWithdrawals();
            affordableWithdrawals();
            concurrentDeposits();
            ledgerFailure();
            fullWithdrawal();
            System.out.println("PASS: service validation, competing withdrawals, concurrent deposits, exact balances and atomic ledger rollback (JDBC locking model).");
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    private static Session session() {
        return new Session(new AuthenticatedUser(ACCOUNT_ID, "test-application", "Test User"));
    }

    private static void validation() throws Exception {
        database = new Database("0.00", false);
        Session session = session();
        for (BigDecimal amount : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-1.00"),
                new BigDecimal("0.001"), new BigDecimal("100000000000000000.00")}) {
            for (boolean deposit : new boolean[]{true, false}) {
                try {
                    if (deposit) SERVICE.deposit(session, amount); else SERVICE.withdraw(session, amount);
                    throw new AssertionError("Reject missing, nonpositive or unsupported amounts");
                } catch (IllegalArgumentException expected) { }
            }
        }
        check(database.connections.get() == 0, "Invalid amounts must not open a connection");
        Account result = SERVICE.deposit(session, new BigDecimal("0.1000"));
        check(result.balance().equals(new BigDecimal("0.10")) && database.ledger.get(0).amount().equals(new BigDecimal("0.10")),
                "Losslessly normalize trailing-zero precision to currency scale");
        session.logout();
        try { SERVICE.withdraw(session, new BigDecimal("0.01")); throw new AssertionError("Reject an expired session"); }
        catch (IllegalStateException expected) { }
        check(database.connections.get() == 1 && database.closed.get() == 1, "Expired sessions must not open another connection");
    }

    private static void competingWithdrawals() throws Exception {
        database = new Database("100.00", true);
        List<Boolean> results = runPair(() -> withdraw(new BigDecimal("75.00")), () -> withdraw(new BigDecimal("75.00")));
        check(results.equals(List.of(true, false)), "Only one competing withdrawal may spend the same funds");
        check(database.balance.equals(new BigDecimal("25.00")) && database.ledger.size() == 1,
                "Concurrent withdrawals must not overdraw or record rejected operations");
        check(database.commits.get() == 1 && database.rollbacks.get() == 1, "Commit one withdrawal and roll back the rejected one");
        verifyConnections();
    }

    private static void affordableWithdrawals() throws Exception {
        database = new Database("100.00", true);
        check(runPair(() -> withdraw(new BigDecimal("40.00")), () -> withdraw(new BigDecimal("40.00"))).equals(List.of(true, true)),
                "Allow both withdrawals when the committed balance covers them");
        check(database.balance.equals(new BigDecimal("20.00")) && database.ledger.size() == 2,
                "The second withdrawal must use the latest locked balance");
        verifyConnections();
    }

    private static void concurrentDeposits() throws Exception {
        database = new Database("0.00", true);
        runPair(() -> { SERVICE.deposit(session(), new BigDecimal("0.10")); return true; },
                () -> { SERVICE.deposit(session(), new BigDecimal("0.20")); return true; });
        check(database.balance.equals(new BigDecimal("0.30")) && database.ledger.size() == 2,
                "Concurrent deposits must not lose updates or floating-point precision");
        verifyConnections();
    }

    private static void ledgerFailure() throws Exception {
        database = new Database("100.00", false);
        database.failLedger = true;
        try { SERVICE.withdraw(session(), new BigDecimal("25.00")); throw new AssertionError("Expect the ledger insert failure"); }
        catch (SQLException expected) { }
        check(database.balance.equals(new BigDecimal("100.00")) && database.ledger.isEmpty(),
                "A failed ledger insert must undo the pending balance update");
        check(database.rollbacks.get() == 1 && database.commits.get() == 0 && database.closed.get() == 1,
                "Explicitly roll back and close failed operations");
    }

    private static void fullWithdrawal() throws Exception {
        database = new Database("1.00", false);
        Account result = SERVICE.withdraw(session(), new BigDecimal("1.00"));
        check(result.balance().equals(new BigDecimal("0.00")) && database.ledger.size() == 1,
                "Allow withdrawing exactly the available balance");
    }

    private static boolean withdraw(BigDecimal amount) throws SQLException {
        try { SERVICE.withdraw(session(), amount); return true; }
        catch (AccountOperationException e) {
            check(e.getReason() == AccountOperationException.Reason.INSUFFICIENT_FUNDS, "Reject the overdraft with the business outcome");
            return false;
        }
    }

    private static List<Boolean> runPair(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one = pool.submit(first);
            check(database.firstLocked.await(5, TimeUnit.SECONDS), "First operation acquired the row lock");
            Future<Boolean> two = pool.submit(second);
            check(database.secondAttempt.await(5, TimeUnit.SECONDS), "Second operation attempted the same row while locked");
            database.releaseFirst.countDown();
            return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        } finally {
            database.releaseFirst.countDown();
        }
    }

    private static void verifyConnections() {
        check(database.connections.get() == 2 && database.closed.get() == 2 && !database.row.isLocked(),
                "Use independent connections and release transaction locks for both sessions");
    }

    private record Entry(String accountId, TransactionType type, BigDecimal amount, LocalDateTime occurredAt) { }

    private static final class Database {
        private final ReentrantLock row = new ReentrantLock(true);
        private final AtomicInteger connections = new AtomicInteger(), closed = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger(), rollbacks = new AtomicInteger(), reads = new AtomicInteger();
        private final CountDownLatch firstLocked = new CountDownLatch(1), secondAttempt = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        private final List<Entry> ledger = new ArrayList<>();
        private final boolean gateFirst;
        private BigDecimal balance;
        private boolean failLedger;

        private Database(String balance, boolean gateFirst) {
            this.balance = new BigDecimal(balance);
            this.gateFirst = gateFirst;
        }

        private Connection connection() {
            connections.incrementAndGet();
            return new TransactionConnection(this).proxy();
        }
    }

    private static final class TransactionConnection {
        private final Database db;
        private boolean autoCommit = true, locked, closed;
        private BigDecimal balance;
        private final List<Entry> ledger = new ArrayList<>();

        private TransactionConnection(Database db) { this.db = db; }

        private Connection proxy() {
            return proxyOf(Connection.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "setAutoCommit": autoCommit = (boolean) args[0]; check(!autoCommit, "Disable auto-commit"); return null;
                    case "prepareStatement": return statement((String) args[0]);
                    case "commit":
                        check(locked && balance != null && ledger.size() == 1, "Commit both a balance update and one ledger entry");
                        db.balance = balance;
                        db.ledger.addAll(ledger);
                        db.commits.incrementAndGet();
                        release();
                        return null;
                    case "rollback": db.rollbacks.incrementAndGet(); release(); return null;
                    case "close":
                        check(!closed, "Close each connection once");
                        closed = true;
                        db.closed.incrementAndGet();
                        release();
                        return null;
                    default: throw new AssertionError("Unexpected connection operation: " + method.getName());
                }
            });
        }

        private PreparedStatement statement(String sql) {
            Map<Integer, Object> values = new HashMap<>();
            return proxyOf(PreparedStatement.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "setString", "setBigDecimal", "setObject": values.put((int) args[0], args[1]); return null;
                    case "executeQuery":
                        check(!autoCommit && sql.endsWith("WHERE account_id = ? FOR UPDATE"), "Lock the primary-key account row in the transaction");
                        check(ACCOUNT_ID.equals(values.get(1)), "Use the authenticated session identity");
                        int read = db.reads.incrementAndGet();
                        if (read == 2) db.secondAttempt.countDown();
                        if (!db.row.tryLock(5, TimeUnit.SECONDS)) throw new SQLException("Test row lock timed out");
                        locked = true;
                        if (db.gateFirst && read == 1) {
                            db.firstLocked.countDown();
                            if (!db.releaseFirst.await(5, TimeUnit.SECONDS)) throw new SQLException("Test coordination timed out");
                        }
                        return resultSet(db.balance);
                    case "executeUpdate":
                        check(!autoCommit && locked && db.row.isHeldByCurrentThread(), "Keep the row locked through every write");
                        if (sql.startsWith("UPDATE accounts SET balance = ?")) {
                            check(ACCOUNT_ID.equals(values.get(2)), "Update only the authenticated account");
                            balance = (BigDecimal) values.get(1);
                            check(balance.signum() >= 0, "Never persist a negative balance");
                        } else if (sql.startsWith("INSERT INTO transactions ")) {
                            if (db.failLedger) throw new SQLException("Injected ledger failure");
                            check(ACCOUNT_ID.equals(values.get(1)), "Record the same account in the ledger");
                            LocalDateTime time = (LocalDateTime) values.get(4);
                            check(time.getNano() % 1000 == 0, "Persist microsecond timestamps");
                            ledger.add(new Entry((String) values.get(1), TransactionType.valueOf((String) values.get(2)),
                                    (BigDecimal) values.get(3), time));
                        } else throw new AssertionError("Unexpected write: " + sql);
                        return 1;
                    case "close": return null;
                    default: throw new AssertionError("Unexpected statement operation: " + method.getName());
                }
            });
        }

        private ResultSet resultSet(BigDecimal snapshot) {
            boolean[] visited = {false};
            return proxyOf(ResultSet.class, (p, method, args) -> {
                return switch (method.getName()) {
                    case "next" -> { boolean result = !visited[0]; visited[0] = true; yield result; }
                    case "getString" -> args[0].equals("account_id") ? ACCOUNT_ID : "test-application";
                    case "getBigDecimal" -> snapshot;
                    case "getBoolean" -> true;
                    case "close" -> null;
                    default -> throw new AssertionError("Unexpected result operation: " + method.getName());
                };
            });
        }

        private void release() {
            balance = null;
            ledger.clear();
            if (locked) { locked = false; db.row.unlock(); }
        }
    }

    private static <T> T proxyOf(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestDriver implements Driver {
        public Connection connect(String url, Properties info) { return acceptsURL(url) ? database.connection() : null; }
        public boolean acceptsURL(String url) { return URL.equals(url); }
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        public int getMajorVersion() { return 1; }
        public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
    }
}
