package atm.dao;

import atm.db.Conn;
import atm.model.AuthenticatedUser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class AuthenticationDao {
    /** Returns the matching user, or null when the card and PIN do not match. */
    public AuthenticatedUser authenticate(String cardno, String pin) throws SQLException {
        String sql = "SELECT l.formno, s.name FROM login l " +
                "JOIN signup s ON l.formno = s.formno " +
                "WHERE l.cardno = ? AND l.pin = ?";

        try (Connection conn = Conn.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cardno);
            ps.setString(2, pin);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new AuthenticatedUser(rs.getString("formno"), rs.getString("name"));
                }
                return null;
            }
        }
    }
}
