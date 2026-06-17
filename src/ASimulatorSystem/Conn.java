package ASimulatorSystem;

import java.sql.*;

public class Conn {
    private static final String URL = "jdbc:mysql://localhost/bankdb";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "hriday@7374";

    Connection c;

    public Conn() throws Exception {
//        Class.forName("com.mysql.jdbc.driver");
        c = DriverManager.getConnection(URL,USERNAME,PASSWORD);
    }
}
