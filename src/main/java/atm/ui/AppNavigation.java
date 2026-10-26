package atm.ui;

import atm.dao.AuthenticationDao;
import atm.dao.RegistrationDao;
import atm.service.AccountService;
import atm.model.Registration;
import atm.model.Session;
import javax.swing.JFrame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** Creates each screen with its dependencies and disposes the previous screen. */
public final class AppNavigation {
    private final RegistrationDao registrationDao;
    private final AuthenticationDao authenticationDao;
    private final AccountService accountService;
    private JFrame currentScreen;

    public AppNavigation(RegistrationDao registrationDao, AuthenticationDao authenticationDao,
                         AccountService accountService) {
        this.registrationDao = registrationDao;
        this.authenticationDao = authenticationDao;
        this.accountService = accountService;
    }

    public void showLogin() {
        show(new Login(authenticationDao, this));
    }

    public void showRegistration() {
        showRegistrationScreen(new Signup(new Registration(), this));
    }

    void showSignup2(Registration registration) {
        showRegistrationScreen(new Signup2(registration, this));
    }

    void showSignup3(Registration registration) {
        showRegistrationScreen(new Signup3(registration, registrationDao, this));
    }

    void showDashboard(Session session) {
        show(new Dashboard(session, accountService, authenticationDao, this));
    }

    private void showRegistrationScreen(JFrame screen) {
        screen.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        screen.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (currentScreen == screen) showLogin();
            }
        });
        show(screen);
    }

    private void show(JFrame screen) {
        JFrame previous = currentScreen;
        currentScreen = screen;
        screen.setVisible(true);
        if (previous != null) previous.dispose();
    }
}
