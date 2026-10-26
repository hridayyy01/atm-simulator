package atm.dao;

import atm.db.Conn;
import atm.model.AuthenticatedUser;
import atm.security.PinHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class AuthenticationDao {
    /** Returns the matching user, or null when the card and PIN do not match. */
    public AuthenticatedUser authenticate(String cardno, char[] pin) throws SQLException {
        if (!PinHasher.isValidPin(pin)) return null;
        String sql = "SELECT l.formno, s.name, l.pin_hash, a.account_id FROM login l " +
                "JOIN signup s ON l.formno = s.formno " +
                "JOIN accounts a ON a.formno = l.formno " +
                "WHERE l.cardno = ?";

        try (Connection conn = Conn.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cardno);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && PinHasher.verify(pin, rs.getString("pin_hash"))) {
                    return new AuthenticatedUser(rs.getString("account_id"), rs.getString("formno"), rs.getString("name"));
                }
                return null;
            }
        }
    }
}
