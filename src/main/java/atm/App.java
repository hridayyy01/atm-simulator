package atm;

import atm.dao.AuthenticationDao;
import atm.dao.RegistrationDao;
import atm.dao.AccountDao;
import atm.dao.TransactionDao;
import atm.ui.AppNavigation;

import javax.swing.*;

public class App {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new AppNavigation(new RegistrationDao(), new AuthenticationDao(),
                        new AccountDao(), new TransactionDao()).showLogin();
            }
        });
    }
}
