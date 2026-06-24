package ASimulatorSystem;

import constants.CommonConstants;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class Login extends JFrame {
    JTextField cardnoField;
    JTextField pinField;
    JButton loginBtn;

    Login() {
        setTitle("ATM Login");
        setSize(400,250);
        setLayout(null);
        setLocationRelativeTo(null);
        addGUIComponents();
    }

    private void addGUIComponents() {
        JLabel cardLabel = new JLabel("Card no : ");
        cardLabel.setBounds(CommonConstants.labelX4,30,CommonConstants.labelW4,CommonConstants.rowH);
        add(cardLabel);

        cardnoField = new JTextField();
        cardnoField.setBounds(CommonConstants.fieldX4,30,CommonConstants.fieldW4,CommonConstants.rowH);
        add(cardnoField);

        JLabel pinLabel = new JLabel("Pin no : ");
        pinLabel.setBounds(CommonConstants.labelX4,60,CommonConstants.labelW4,CommonConstants.rowH);
        add(pinLabel);

        pinField = new JTextField();
        pinField.setBounds(CommonConstants.fieldX4,60,CommonConstants.fieldW4,CommonConstants.rowH);
        add(pinField);

        loginBtn = new JButton("Login");
        loginBtn.setBounds(200,150,100,60);
        add(loginBtn);
        loginBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent ae) {
                String cardno = cardnoField.getText().trim();
                String pin = pinField.getText().trim();

                if(cardno.isEmpty() || pin.isEmpty()) {
                    JOptionPane.showMessageDialog(Login.this,"Card no and pin no are required");
                    return;
                }

                if(!cardno.matches("\\d{16}")) {
                    JOptionPane.showMessageDialog(Login.this,"Card no must be 16 digits.");
                    return;
                }

                if(!pin.matches("\\d{4}")) {
                    JOptionPane.showMessageDialog(Login.this,"pin no must be of 4 digits.");
                    return;
                }

                Connection conn = null;
                PreparedStatement ps = null;
                ResultSet rs = null;

                try {
                    Conn c1 = new Conn();
                    conn = c1.c;

                    String sql = "SELECT l.formno, s.name FROM login l "+
                            "JOIN signup s ON l.formno = s.formno "+
                            "WHERE l.cardno = ? AND l.pin = ?";

                    ps = conn.prepareStatement(sql);
                    ps.setString(1,cardno);
                    ps.setString(2,pin);

                    rs = ps.executeQuery();

                    if(rs.next()) {
                        String formno = rs.getString("formno");
                        String holderName = rs.getString("name");

                        JOptionPane.showMessageDialog(Login.this,"Welcome : " + holderName);
                        Login.this.dispose();
//                        new Transaction(cardno ,pin , formno);
                    } else {
                        JOptionPane.showMessageDialog(Login.this,"Invalid Card Number or pin");
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if(rs != null) rs.close();
                        if(ps != null) rs.close();
                        if(conn != null) rs.close();
                    } catch(SQLException se2) {
                        se2.printStackTrace();
                    }
                }
            }
        });

    }

}
