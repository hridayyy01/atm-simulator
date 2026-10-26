package atm.ui;

import atm.dao.AuthenticationDao;
import atm.model.AuthenticatedUser;
import atm.model.Session;
import atm.security.PinHasher;

import javax.swing.*;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

public class Login extends JFrame {
    private final AuthenticationDao authenticationDao;
    private final AppNavigation navigation;
    JTextField cardnoField;
    JPasswordField pinField;
    JButton loginBtn;
    private boolean authenticating;

    Login(AuthenticationDao authenticationDao, AppNavigation navigation) {
        this.authenticationDao = authenticationDao;
        this.navigation = navigation;
        setTitle("ATM Login");
        setSize(400,290);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
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

        pinField = new JPasswordField();
        pinField.setBounds(CommonConstants.fieldX4,60,CommonConstants.fieldW4,CommonConstants.rowH);
        add(pinField);

        loginBtn = new JButton("Login");
        loginBtn.setBounds(200,150,100,60);
        add(loginBtn);
        JButton registerBtn = new JButton("Register");
        registerBtn.setBounds(80,150,110,60);
        registerBtn.addActionListener(e -> navigation.showRegistration());
        add(registerBtn);
        loginBtn.addActionListener(e -> {
            if (authenticating) return;
            String cardno = cardnoField.getText().trim();
            char[] pin = pinField.getPassword();
            pinField.setText("");
            boolean started = false;
            try {
                if(cardno.isEmpty() || pin.length == 0) {
                    JOptionPane.showMessageDialog(Login.this,"Card no and pin no are required");
                    return;
                }
                if(!cardno.matches("\\d{16}")) {
                    JOptionPane.showMessageDialog(Login.this,"Card no must be 16 digits.");
                    return;
                }
                if(!PinHasher.isValidPin(pin)) {
                    JOptionPane.showMessageDialog(Login.this,"pin no must be of 4 digits.");
                    return;
                }
                authenticating = true;
                loginBtn.setEnabled(false);
                registerBtn.setEnabled(false);
                cardnoField.setEnabled(false);
                pinField.setEnabled(false);
                SwingWorker<AuthenticatedUser, Void> worker = new SwingWorker<>() {
                    @Override protected AuthenticatedUser doInBackground() throws Exception {
                        try { return authenticationDao.authenticate(cardno, pin); }
                        finally { Arrays.fill(pin, '\0'); }
                    }
                    @Override protected void done() {
                        try {
                            if (!isDisplayable()) return;
                            AuthenticatedUser user = get();
                            if(user != null) {
                                JOptionPane.showMessageDialog(Login.this,"Welcome : " + user.name());
                                navigation.showDashboard(new Session(user));
                            } else {
                                JOptionPane.showMessageDialog(Login.this,"Invalid Card Number or pin");
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            JOptionPane.showMessageDialog(Login.this,"Unable to log in. Please try again.");
                        } catch (ExecutionException | RuntimeException ex) {
                            JOptionPane.showMessageDialog(Login.this,"Unable to log in. Please try again.");
                        } finally {
                            authenticating = false;
                            loginBtn.setEnabled(true);
                            registerBtn.setEnabled(true);
                            cardnoField.setEnabled(true);
                            pinField.setEnabled(true);
                            pinField.setText("");
                        }
                    }
                };
                worker.execute();
                started = true;
            } catch (RuntimeException ex) {
                authenticating = false;
                loginBtn.setEnabled(true);
                registerBtn.setEnabled(true);
                cardnoField.setEnabled(true);
                pinField.setEnabled(true);
                JOptionPane.showMessageDialog(Login.this,"Unable to log in. Please try again.");
            } finally {
                if (!started) Arrays.fill(pin, '\0');
            }
        });
    }
}
