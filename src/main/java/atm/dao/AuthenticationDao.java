package atm.dao;

import atm.db.Conn;
import atm.model.AuthenticatedUser;
import atm.security.PinHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class AuthenticationDao {
    /** Verifies the current PIN and replaces only this account's salted hash. */
    public boolean changePin(String accountId, char[] currentPin, char[] newPin) throws SQLException {
        if (!PinHasher.isValidPin(currentPin) || !PinHasher.isValidPin(newPin)) {
            throw new IllegalArgumentException("PIN must contain four digits.");
        }
        boolean committed = false;
        try (Connection con = Conn.getConnection()) {
            con.setAutoCommit(false);
            try {
                String cardno;
                String hash;
                try (PreparedStatement ps = con.prepareStatement("SELECT l.cardno, l.pin_hash FROM login l "
                        + "JOIN accounts a ON a.formno = l.formno WHERE a.account_id = ? FOR UPDATE")) {
                    ps.setString(1, accountId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            return false;
                        }
                        cardno = rs.getString("cardno");
                        hash = rs.getString("pin_hash");
                    }
                }
                if (!PinHasher.verify(currentPin, hash)) {
                    con.rollback();
                    return false;
                }
                try (PreparedStatement ps = con.prepareStatement("UPDATE login SET pin_hash = ? WHERE cardno = ?")) {
                    ps.setString(1, PinHasher.hash(newPin));
                    ps.setString(2, cardno);
                    if (ps.executeUpdate() != 1) throw new SQLException("PIN update failed.");
                }
                con.commit();
                committed = true;
                return true;
            } catch (SQLException | RuntimeException e) {
                try { con.rollback(); }
                catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException e) {
            if (committed) return true;
            throw e;
        }
    }

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
