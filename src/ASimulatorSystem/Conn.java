package ASimulatorSystem;

import java.sql.*;

public class Conn {
    private static final String URL = "jdbc:mysql://localhost/bankdb";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "";

    Connection c;
    Statement s;

    public Conn() throws Exception {
        Class.forName("com.mysql.cj.jdbc.driver");
        c = DriverManager.getConnection(URL,USERNAME,PASSWORD);
        s = c.createStatement();
    }
}
