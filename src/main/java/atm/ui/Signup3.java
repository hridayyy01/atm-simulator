package atm.ui;

import atm.dao.AuthenticationDao;
import atm.dao.RegistrationDao;
import atm.model.CardCredentials;
import atm.model.Registration;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.sql.SQLException;

public class Signup3 extends JFrame {
    private final RegistrationDao registrationDao;
    private final AuthenticationDao authenticationDao;
    private final Registration registration;
    private boolean submitting;
    JTextField formnoField;
    JButton generateButton;

    Signup3(Registration registration, RegistrationDao registrationDao, AuthenticationDao authenticationDao) {
        this.registration = registration;
        this.registrationDao = registrationDao;
        this.authenticationDao = authenticationDao;
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

        formnoField = new JTextField(registration.getApplicationId());
        formnoField.setBounds(CommonConstants.fieldX3,60,CommonConstants.fieldW3,CommonConstants.rowH);
        formnoField.setEditable(false);
        add(formnoField);

        generateButton = new JButton("Submit Application");
        generateButton.setBounds(CommonConstants.r3);
        generateButton.setFont(CommonConstants.ButtonFont);
        generateButton.setEnabled(!registration.isSubmitted() && !registration.isOutcomeUnknown());
        add(generateButton);
        generateButton.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent ae) {
                if (submitting || registration.isSubmitted() || registration.isOutcomeUnknown()) return;
                submitting = true;
                generateButton.setEnabled(false);
                try {
                    CardCredentials credentials = registrationDao.submit(registration);
                    formnoField.setText(registration.getApplicationId());
                    JOptionPane.showMessageDialog(Signup3.this,"Account created!\nCard no : " + credentials.getCardno() + "\nPIN : " + credentials.getPin() +"\n please note this down it will not be shown again!");
                    Signup3.this.dispose();
                    new Login(authenticationDao).setVisible(true);
                } catch(SQLException se) {
                    se.printStackTrace();
                    JOptionPane.showMessageDialog(Signup3.this,"Database Error : " + se.getMessage());
                } catch (Exception e) {
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(Signup3.this,"Error : " + e.getMessage());
                } finally {
                    submitting = false;
                    formnoField.setText(registration.getApplicationId());
                    generateButton.setEnabled(!registration.isSubmitted() && !registration.isOutcomeUnknown());
                }
            }
        });
    }
}
