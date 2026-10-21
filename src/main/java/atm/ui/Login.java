package atm.ui;

import atm.dao.AuthenticationDao;
import atm.model.AuthenticatedUser;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class Login extends JFrame {
    private final AuthenticationDao authenticationDao;
    JTextField cardnoField;
    JTextField pinField;
    JButton loginBtn;

    Login(AuthenticationDao authenticationDao) {
        this.authenticationDao = authenticationDao;
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

                try {
                    AuthenticatedUser user = authenticationDao.authenticate(cardno, pin);
                    if(user != null) {
                        JOptionPane.showMessageDialog(Login.this,"Welcome : " + user.name());
                        Login.this.dispose();
                    } else {
                        JOptionPane.showMessageDialog(Login.this,"Invalid Card Number or pin");
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

    }

}
