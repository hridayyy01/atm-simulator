package ASimulatorSystem;

import com.mysql.cj.protocol.Resultset;
import com.mysql.cj.util.StringInspector;
import constants.CommonConstants;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.sql.*;
import java.util.Random;

public class Signup3 extends JFrame {
    String formno;
    JTextField formnoField;
    JButton generateButton;

    Signup3(String formno) {
        this.formno = formno;
        setTitle("ATM signup - step 3");
        setSize(400,400);
        setLayout(null);
        setLocationRelativeTo(null);
        addGUIComponents();
    }

    private void addGUIComponents() {
        JLabel formnoLabel = new JLabel("Form no : ");
        formnoLabel.setBounds(CommonConstants.labelX3,60,CommonConstants.labelW3,CommonConstants.rowH);
        formnoLabel.setFont(CommonConstants.font);
        add(formnoLabel);
        add(formnoLabel);

        formnoField = new JTextField(formno);
        formnoField.setBounds(CommonConstants.fieldX3,60,CommonConstants.fieldW3,CommonConstants.rowH);
        formnoField.setEditable(false);
        add(formnoField);

        generateButton = new JButton("Generate Card and Pin");
        generateButton.setBounds(CommonConstants.r3);
        generateButton.setFont(CommonConstants.ButtonFont);
        add(generateButton);
        generateButton.addActionListener(new ActionListener() {

            private String generateUniqueCardNo(Connection c) throws SQLException {
                Random rand = new Random();
                String cardno;
                boolean exists;

                do {
                    StringBuilder sb = new StringBuilder();
                    for(int i = 0; i < 16;i++) sb.append(rand.nextInt(10));
                    cardno = sb.toString();

                    String dupCheckSql = "SELECT cardno FROM login WHERE cardno = ?";
                    try(PreparedStatement dupStmt = c.prepareStatement(dupCheckSql)) {
                        dupStmt.setString(1,cardno);
                        try(ResultSet dupRs = dupStmt.executeQuery()) {
                            exists = dupRs.next();
                        }
                    }
                } while (exists);
                return cardno;
            }

            private String generatePin() {
                Random rand = new Random();
                int pinNum = 1000 + rand.nextInt(9000);
                return String.valueOf(pinNum);
            }

            @Override
            public void actionPerformed(ActionEvent ae) {
                Connection con = null;
                PreparedStatement checkStmt = null, insertStmt = null;
                ResultSet rs = null;

                try {
                    Conn c1 = new Conn();
                    con = c1.c;

                    String checkSql = "SELECT s.formno FROM signup s " +
                            "JOIN signuptwo st " +
                            "ON s.formno = st.formno " +
                            "WHERE s.formno = ?" +
                            "AND NOT EXISTS (SELECT 1 FROM login l WHERE l.formno = s.formno)";

                    checkStmt = con.prepareStatement(checkSql);
                    checkStmt.setString(1,formno);
                    rs = checkStmt.executeQuery();

                    if(!rs.next()) {
                        JOptionPane.showMessageDialog(Signup3.this,"Signup incomplete or card already issued for this form");
                        return;
                    }

                    String cardno = generateUniqueCardNo(con);
                    String pin = generatePin();

                    String insertSql = "INSERT INTO LOGIN (cardno, pin, formno) VALUES (?, ?, ?)";
                    insertStmt = con.prepareStatement(insertSql);
                    insertStmt.setString(1, cardno);
                    insertStmt.setString(2,pin);
                    insertStmt.setString(3,formno);

                    int rows = insertStmt.executeUpdate();
                    if(rows > 0) {
                        JOptionPane.showMessageDialog(Signup3.this,"Account created!\nCard no : " + cardno + "\nPIN : " + pin +"\n please note this down it will not be shown again!");
                        Signup3.this.dispose();
//                        new Login();
                    }
                } catch(SQLException se) {
                    se.printStackTrace();
                    JOptionPane.showMessageDialog(Signup3.this,"Database Error : " + se.getMessage());
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if (rs != null) rs.close();
                        if (checkStmt != null) checkStmt.close();
                        if (insertStmt != null) insertStmt.close();
                    } catch (SQLException sx) {
                        sx.printStackTrace();
                    }
                }
            }
        });

    }
}
