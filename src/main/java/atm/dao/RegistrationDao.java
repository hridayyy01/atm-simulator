package atm.dao;

import atm.db.Conn;
import atm.model.AdditionalDetails;
import atm.model.CardCredentials;
import atm.model.CardIssueResult;
import atm.model.PersonalDetails;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;

public class RegistrationDao {
    public int savePersonalDetails(PersonalDetails details) throws SQLException {
        String sql = "INSERT INTO signup (formno, name, fname, gender, email, marital, address, city, pincode, state)" + "VALUES ( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection con = Conn.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, details.formno());
            ps.setString(2, details.name());
            ps.setString(3, details.fatherName());
            ps.setString(4, details.gender());
            ps.setString(5, details.email());
            ps.setString(6, details.maritalStatus());
            ps.setString(7, details.address());
            ps.setString(8, details.city());
            ps.setString(9, details.pincode());
            ps.setString(10, details.state());
            return ps.executeUpdate();
        }
    }

    public void saveAdditionalDetails(AdditionalDetails details) throws SQLException {
        String sql = "INSERT INTO signuptwo(formno,religion,category,income,education,occupation,pan,aadhar,isSenior)" + "VALUES( ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection con = Conn.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, details.formno());
            ps.setString(2, details.religion());
            ps.setString(3, details.category());
            ps.setString(4, details.income());
            ps.setString(5, details.education());
            ps.setString(6, details.occupation());
            ps.setString(7, details.pan());
            ps.setString(8, details.aadhar());
            ps.setString(9, details.senior());
            ps.executeUpdate();
        }
    }

    public CardIssueResult issueCard(String formno) throws SQLException {
        String checkSql = "SELECT s.formno FROM signup s " +
                "JOIN signuptwo st " +
                "ON s.formno = st.formno " +
                "WHERE s.formno = ?" +
                "AND NOT EXISTS (SELECT 1 FROM login l WHERE l.formno = s.formno)";

        try (Connection con = Conn.getConnection();
             PreparedStatement checkStmt = con.prepareStatement(checkSql)) {
            checkStmt.setString(1, formno);
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (!rs.next()) {
                    return new CardIssueResult(CardIssueResult.Status.INCOMPLETE_OR_ALREADY_ISSUED, null);
                }
            }

            String cardno = generateUniqueCardNo(con);
            String pin = generatePin();
            String insertSql = "INSERT INTO login (cardno, pin, formno) VALUES (?, ?, ?)";
            try (PreparedStatement insertStmt = con.prepareStatement(insertSql)) {
                insertStmt.setString(1, cardno);
                insertStmt.setString(2, pin);
                insertStmt.setString(3, formno);

                int rows = insertStmt.executeUpdate();
                if (rows > 0) {
                    return new CardIssueResult(CardIssueResult.Status.ISSUED,
                            new CardCredentials(cardno, pin));
                }
                return new CardIssueResult(CardIssueResult.Status.INSERT_FAILED, null);
            }
        }
    }

    private String generateUniqueCardNo(Connection con) throws SQLException {
        Random rand = new Random();
        String cardno;
        boolean exists;

        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(rand.nextInt(10));
            cardno = sb.toString();

            String dupCheckSql = "SELECT cardno FROM login WHERE cardno = ?";
            try (PreparedStatement dupStmt = con.prepareStatement(dupCheckSql)) {
                dupStmt.setString(1, cardno);
                try (ResultSet dupRs = dupStmt.executeQuery()) {
                    exists = dupRs.next();
                }
            }
        } while (exists);
        return cardno;
    }

    private String generatePin() {
        Random rand = new Random();
        return String.valueOf(1000 + rand.nextInt(9000));
    }
}
