package atm.dao;

import atm.db.Conn;
import atm.model.Account;
import atm.model.Money;
import atm.model.TransactionType;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public class AccountDao {
    /** Updates the balance and ledger together under the account row lock. */
    public Account postTransaction(String accountId, BigDecimal amount, TransactionType type) throws SQLException {
        Objects.requireNonNull(type);
        amount = Money.normalize(amount);
        if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive.");
        Account result = null;
        boolean committed = false;
        try (Connection con = Conn.getConnection()) {
            con.setAutoCommit(false);
            try {
                Account current;
                try (PreparedStatement ps = con.prepareStatement("SELECT account_id, formno, balance, balance_verified "
                        + "FROM accounts WHERE account_id = ? FOR UPDATE")) {
                    ps.setString(1, accountId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new AccountOperationException(AccountOperationException.Reason.ACCOUNT_NOT_FOUND);
                        current = new Account(rs.getString("account_id"), rs.getString("formno"),
                                rs.getBigDecimal("balance"), rs.getBoolean("balance_verified"));
                    }
                }
                if (!current.balanceVerified()) {
                    throw new AccountOperationException(AccountOperationException.Reason.BALANCE_UNVERIFIED);
                }
                if (type == TransactionType.WITHDRAWAL && amount.compareTo(current.balance()) > 0) {
                    throw new AccountOperationException(AccountOperationException.Reason.INSUFFICIENT_FUNDS);
                }
                BigDecimal balance;
                try {
                    balance = Money.normalize(type == TransactionType.DEPOSIT
                            ? current.balance().add(amount) : current.balance().subtract(amount));
                } catch (IllegalArgumentException e) {
                    throw new AccountOperationException(AccountOperationException.Reason.BALANCE_LIMIT);
                }
                try (PreparedStatement ps = con.prepareStatement("UPDATE accounts SET balance = ? WHERE account_id = ?")) {
                    ps.setBigDecimal(1, balance);
                    ps.setString(2, accountId);
                    requireOneRow(ps.executeUpdate());
                }
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO transactions "
                        + "(account_id, transaction_type, amount, occurred_at) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, accountId);
                    ps.setString(2, type.name());
                    ps.setBigDecimal(3, amount);
                    ps.setObject(4, LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
                    requireOneRow(ps.executeUpdate());
                }
                result = new Account(accountId, current.formno(), balance, true);
                con.commit();
                committed = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                try { con.rollback(); }
                catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException e) {
            // A cleanup error after an acknowledged commit must not invite a duplicate deposit.
            if (committed) return result;
            throw e;
        }
    }

    private void requireOneRow(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("Account operation failed.");
    }

    public Account findById(String accountId) throws SQLException {
        String sql = "SELECT account_id, formno, balance, balance_verified FROM accounts WHERE account_id = ?";
        try (Connection con = Conn.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Account(rs.getString("account_id"), rs.getString("formno"),
                        rs.getBigDecimal("balance"), rs.getBoolean("balance_verified"));
            }
        }
    }
}
