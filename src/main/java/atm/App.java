package atm;

import atm.dao.AuthenticationDao;
import atm.dao.RegistrationDao;
import atm.ui.Signup;

import javax.swing.*;

public class App {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                RegistrationDao registrationDao = new RegistrationDao();
                AuthenticationDao authenticationDao = new AuthenticationDao();
                new Signup(registrationDao, authenticationDao).setVisible(true);
            }
        });
    }
}
