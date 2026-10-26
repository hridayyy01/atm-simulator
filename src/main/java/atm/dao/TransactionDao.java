package atm.dao;

import atm.db.Conn;
import atm.model.Transaction;
import atm.model.TransactionType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

public class TransactionDao {
    public List<Transaction> findByAccountId(String accountId) throws SQLException {
        return readHistory(accountId, false);
    }

    public List<Transaction> findRecentByAccountId(String accountId) throws SQLException {
        return readHistory(accountId, true);
    }

    private List<Transaction> readHistory(String accountId, boolean recent) throws SQLException {
        String sql = "SELECT transaction_id, account_id, transaction_type, amount, occurred_at "
                + "FROM transactions WHERE account_id = ? "
                + (recent ? "ORDER BY occurred_at DESC, transaction_id DESC LIMIT 10" : "ORDER BY occurred_at, transaction_id");
        try (Connection con = Conn.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, accountId);
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
