package atm.dao;

import atm.db.Conn;
import atm.model.Account;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class AccountDao {
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
