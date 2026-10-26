package atm;

import atm.dao.AuthenticationDao;
import atm.dao.RegistrationDao;
import atm.dao.AccountDao;
import atm.dao.TransactionDao;
import atm.service.AccountService;
import atm.ui.AppNavigation;

import javax.swing.*;

public class App {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                AccountService accountService = new AccountService(new AccountDao(), new TransactionDao());
                new AppNavigation(new RegistrationDao(), new AuthenticationDao(), accountService).showLogin();
            }
        });
    }
}
