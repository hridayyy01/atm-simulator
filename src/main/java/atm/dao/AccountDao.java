package atm.dao;

import atm.db.Conn;
import atm.model.Account;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class AccountDao {
    /** Holds the account row until the caller commits or rolls back. */
    public Account findByIdForUpdate(Connection con, String accountId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT account_id, formno, balance, balance_verified "
                + "FROM accounts WHERE account_id = ? FOR UPDATE")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Account(rs.getString("account_id"), rs.getString("formno"),
                        rs.getBigDecimal("balance"), rs.getBoolean("balance_verified"));
            }
        }
    }

    public void updateBalance(Connection con, String accountId, BigDecimal balance) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE accounts SET balance = ? WHERE account_id = ?")) {
            ps.setBigDecimal(1, balance);
            ps.setString(2, accountId);
            if (ps.executeUpdate() != 1) throw new SQLException("Account balance update failed.");
        }
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
