package atm.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Conn {
    private Conn() {
    }

    /**
     * Opens a connection using the required DB_URL, DB_USERNAME and DB_PASSWORD
     * environment variables. Missing or blank values cause an SQLException.
     */
    public static Connection getConnection() throws SQLException {
        String url = requireEnvironmentVariable("DB_URL");
        String username = requireEnvironmentVariable("DB_USERNAME");
        String password = requireEnvironmentVariable("DB_PASSWORD");
        return DriverManager.getConnection(url, username, password);
    }

    private static String requireEnvironmentVariable(String name) throws SQLException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new SQLException("Missing database configuration: environment variable "
                    + name + " must be set and non-blank.");
        }
        return value;
    }
}
