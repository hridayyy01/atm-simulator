package atm.tools;

import atm.db.Conn;
import atm.security.PinHasher;

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/** Offline, transactional upgrade between SQL migrations 003 and 004. */
public final class MigratePins {
    private MigratePins() { }

    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("Use environment database configuration; this tool accepts no arguments.");
            System.exit(1);
        }
        try (Connection con = Conn.getConnection()) {
            int count = migrate(con);
            System.out.println("PIN migration complete: " + count + " records upgraded. Apply migration 004 next.");
        } catch (SQLException | RuntimeException e) {
            // Driver exceptions can include SQL values. Never print them or their causes.
            System.err.println("PIN migration failed. No success was confirmed. Review the upgrade instructions before retrying.");
            System.exit(1);
        }
    }

    /** Uses a dedicated connection; reruns safely skip rows already upgraded. */
    public static int migrate(Connection con) throws SQLException {
        con.setAutoCommit(false);
        int count = 0;
        try {
            try (PreparedStatement select = con.prepareStatement(
                    "SELECT cardno, pin, pin_hash FROM login FOR UPDATE");
                 PreparedStatement update = con.prepareStatement(
                    "UPDATE login SET pin_hash = ?, pin = NULL WHERE cardno = ?");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    String existingHash = rows.getString("pin_hash");
                    try (Reader reader = rows.getCharacterStream("pin")) {
                        if (reader == null) {
                            if (existingHash == null) throw new SQLException("A credential requires manual review.");
                            continue;
                        }
                        char[] pin = readPin(reader);
                        try {
                            if (existingHash != null && !PinHasher.verify(pin, existingHash)) {
                                throw new SQLException("A credential requires manual review.");
                            }
                            String hash = existingHash == null ? PinHasher.hash(pin) : existingHash;
                            update.setString(1, hash);
                            update.setString(2, rows.getString("cardno"));
                            if (update.executeUpdate() != 1) throw new SQLException("Credential update failed.");
                            count++;
                        } finally {
                            Arrays.fill(pin, '\0');
                        }
                    } catch (IOException e) {
                        throw new SQLException("Unable to read a legacy credential.");
                    }
                }
            }
            con.commit();
            return count;
        } catch (SQLException | RuntimeException e) {
            try {
                con.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        }
    }

    private static char[] readPin(Reader reader) throws IOException, SQLException {
        char[] pin = new char[4];
        boolean valid = false;
        try {
            for (int i = 0; i < pin.length; i++) {
                int digit = reader.read();
                if (digit < '0' || digit > '9') throw new SQLException("A legacy credential requires manual review.");
                pin[i] = (char) digit;
            }
            if (reader.read() != -1) throw new SQLException("A legacy credential requires manual review.");
            valid = true;
            return pin;
        } finally {
            if (!valid) Arrays.fill(pin, '\0');
        }
    }
}
