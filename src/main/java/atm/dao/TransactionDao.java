package atm.dao;

import atm.db.Conn;
import atm.model.Transaction;
import atm.model.TransactionType;
import atm.model.Session;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;

public class TransactionDao {
    /** Participates in the caller's transaction without opening or committing a connection. */
    public void insert(Connection con, Session session, TransactionType type, BigDecimal amount, Instant occurredAt) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO transactions "
                + "(account_id, transaction_type, amount, occurred_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, session.getAccountId());
            ps.setString(2, type.name());
            ps.setBigDecimal(3, amount);
            ps.setObject(4, LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
            if (ps.executeUpdate() != 1) throw new SQLException("Transaction insert failed.");
        }
    }

    public List<Transaction> findForSession(Session session) throws SQLException {
        return readHistory(session, false);
    }

    public List<Transaction> findRecentForSession(Session session) throws SQLException {
        return readHistory(session, true);
    }

    private List<Transaction> readHistory(Session session, boolean recent) throws SQLException {
        String accountId = session.getAccountId();
        String formno = session.getFormno();
        String sql = "SELECT t.transaction_id, t.account_id, t.transaction_type, t.amount, t.occurred_at "
                + "FROM transactions t JOIN accounts a ON a.account_id = t.account_id "
                + "WHERE t.account_id = ? AND a.formno = ? "
                + (recent ? "ORDER BY t.occurred_at DESC, t.transaction_id DESC LIMIT 10" : "ORDER BY t.occurred_at, t.transaction_id");
        try (Connection con = Conn.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, accountId);
            ps.setString(2, formno);
            try (ResultSet rs = ps.executeQuery()) {
                List<Transaction> transactions = new ArrayList<>();
                while (rs.next()) {
                    transactions.add(new Transaction(rs.getLong("transaction_id"), rs.getString("account_id"),
                            TransactionType.valueOf(rs.getString("transaction_type")), rs.getBigDecimal("amount"),
                            rs.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)));
                }
                return List.copyOf(transactions);
            }
        }
    }
}
