package atm.ui;

import atm.dao.AuthenticationDao;
import atm.service.AccountService;
import atm.service.AccountOperationException;
import atm.model.Money;
import atm.model.Session;
import atm.model.Transaction;
import atm.model.TransactionType;
import atm.security.PinHasher;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public final class Dashboard extends JFrame {
    private final Session session;
    private final AccountService accountService;
    private final AuthenticationDao authenticationDao;
    private final AppNavigation navigation;
    private final List<JButton> buttons = new ArrayList<>();
    private final JLabel status = new JLabel("Choose an action.");
    private boolean busy;

    Dashboard(Session session, AccountService accountService,
              AuthenticationDao authenticationDao, AppNavigation navigation) {
        session.requireActive();
        this.session = session;
        this.accountService = accountService;
        this.authenticationDao = authenticationDao;
        this.navigation = navigation;
        setTitle("ATM Dashboard");
        setSize(650, 400);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(16, 16));
        content.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JPanel heading = new JPanel(new GridLayout(2, 1, 0, 8));
        heading.add(new JLabel("Welcome, " + session.getName()));
        heading.add(new JLabel("Account ID: " + session.getAccountId()));
        content.add(heading, BorderLayout.NORTH);
        JPanel actions = new JPanel(new GridLayout(3, 2, 12, 12));
        addAction(actions, "Deposit", () -> postTransaction(TransactionType.DEPOSIT));
        addAction(actions, "Withdrawal", () -> postTransaction(TransactionType.WITHDRAWAL));
        addAction(actions, "Balance", this::showBalance);
        addAction(actions, "Mini Statement", this::showMiniStatement);
        addAction(actions, "Change PIN", this::changePin);
        addAction(actions, "Logout", this::logout);
        content.add(actions, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        setContentPane(content);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { logout(); }
        });
    }

    private void addAction(JPanel panel, String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(e -> {
            if (!busy && session.isActive()) action.run();
        });
        buttons.add(button);
        panel.add(button);
    }

    private void postTransaction(TransactionType type) {
        String raw = JOptionPane.showInputDialog(this, type == TransactionType.DEPOSIT
                ? "Deposit amount:" : "Withdrawal amount:");
        if (raw == null) return;
        final BigDecimal amount;
        try {
            String value = raw.trim();
            if (value.length() > 32 || !value.matches("[0-9]+(?:\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
            amount = Money.normalize(new BigDecimal(value));
            if (amount.signum() <= 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, "Enter a positive amount with at most two decimal places.");
            return;
        }
        String action = type == TransactionType.DEPOSIT ? "Deposit" : "Withdrawal";
        runTask(() -> type == TransactionType.DEPOSIT
                        ? accountService.deposit(session, amount) : accountService.withdraw(session, amount),
                account -> JOptionPane.showMessageDialog(this, action + " successful.\nBalance: " + account.balance().toPlainString()),
                "Unable to confirm the " + action.toLowerCase(java.util.Locale.ROOT)
                        + ". Check balance and mini statement before trying again.");
    }

    private void showBalance() {
        runTask(() -> accountService.getAccount(session), account -> JOptionPane.showMessageDialog(this, account.balanceVerified()
                ? "Balance: " + account.balance().toPlainString()
                : "Balance is pending administrative reconciliation."), "Unable to retrieve the balance. Please try again.");
    }

    private void showMiniStatement() {
        runTask(() -> accountService.getRecentTransactions(session), this::displayStatement,
                "Unable to retrieve the mini statement. Please try again.");
    }

    private void displayStatement(List<Transaction> transactions) {
        if (transactions.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No transactions yet.");
            return;
        }
        DateTimeFormatter time = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
        Object[][] rows = new Object[transactions.size()][3];
        for (int i = 0; i < transactions.size(); i++) {
            Transaction transaction = transactions.get(i);
            rows[i] = new Object[]{time.format(transaction.occurredAt()), transaction.type().name(), transaction.amount().toPlainString()};
        }
        DefaultTableModel model = new DefaultTableModel(rows, new String[]{"Time (UTC)", "Type", "Amount"}) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable table = new JTable(model);
        table.setPreferredScrollableViewportSize(new Dimension(560, 200));
        JOptionPane.showMessageDialog(this, new JScrollPane(table), "Mini Statement — latest 10", JOptionPane.PLAIN_MESSAGE);
    }

    private void changePin() {
        JPasswordField currentField = new JPasswordField();
        JPasswordField newField = new JPasswordField();
        JPasswordField confirmField = new JPasswordField();
        JPanel form = new JPanel(new GridLayout(3, 2, 8, 8));
        form.add(new JLabel("Current PIN:")); form.add(currentField);
        form.add(new JLabel("New PIN:")); form.add(newField);
        form.add(new JLabel("Confirm new PIN:")); form.add(confirmField);
        int choice = JOptionPane.showConfirmDialog(this, form, "Change PIN", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] current = currentField.getPassword();
        char[] next = newField.getPassword();
        char[] confirmation = confirmField.getPassword();
        currentField.setText(""); newField.setText(""); confirmField.setText("");
        boolean started = false;
        try {
            if (choice != JOptionPane.OK_OPTION) return;
            if (!PinHasher.isValidPin(current) || !PinHasher.isValidPin(next)) {
                JOptionPane.showMessageDialog(this, "PINs must contain exactly four digits.");
                return;
            }
            if (!Arrays.equals(next, confirmation)) {
                JOptionPane.showMessageDialog(this, "New PIN and confirmation do not match.");
                return;
            }
            if (Arrays.equals(current, next)) {
                JOptionPane.showMessageDialog(this, "Choose a PIN different from the current PIN.");
                return;
            }
            started = runTask(() -> {
                try { return authenticationDao.changePin(session.getAccountId(), current, next); }
                finally { Arrays.fill(current, '\0'); Arrays.fill(next, '\0'); }
            }, changed -> JOptionPane.showMessageDialog(this, changed ? "PIN changed successfully." : "Current PIN is incorrect."),
                    "Unable to confirm the PIN change. Log out and verify your credentials before retrying.");
        } finally {
            Arrays.fill(confirmation, '\0');
            if (!started) { Arrays.fill(current, '\0'); Arrays.fill(next, '\0'); }
        }
    }

    private <T> boolean runTask(Callable<T> work, Consumer<T> success, String failureMessage) {
        if (busy || !session.isActive()) return false;
        setBusy(true);
        SwingWorker<T, Void> worker = new SwingWorker<>() {
            @Override protected T doInBackground() throws Exception {
                return work.call();
            }
            @Override protected void done() {
                try {
                    if (!session.isActive()) return;
                    T result = get();
                    if (session.isActive()) success.accept(result);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause instanceof AccountOperationException operation
                            ? operationMessage(operation.getReason()) : failureMessage;
                    JOptionPane.showMessageDialog(Dashboard.this, message);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    JOptionPane.showMessageDialog(Dashboard.this, failureMessage);
                } catch (RuntimeException e) {
                    JOptionPane.showMessageDialog(Dashboard.this, failureMessage);
                } finally {
                    setBusy(false);
                }
            }
        };
        try {
            worker.execute();
            return true;
        } catch (RuntimeException e) {
            setBusy(false);
            JOptionPane.showMessageDialog(this, failureMessage);
            return false;
        }
    }

    private String operationMessage(AccountOperationException.Reason reason) {
        return switch (reason) {
            case ACCOUNT_NOT_FOUND -> "Account not found. Please log out and contact the administrator.";
            case BALANCE_UNVERIFIED -> "This balance needs administrative reconciliation before deposits or withdrawals.";
            case INSUFFICIENT_FUNDS -> "Insufficient funds.";
            case BALANCE_LIMIT -> "This deposit would exceed the supported balance limit.";
        };
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        for (JButton button : buttons) button.setEnabled(!busy && session.isActive());
        status.setText(busy ? "Processing..." : "Choose an action.");
    }

    private void logout() {
        if (!session.isActive()) return;
        if (busy) {
            JOptionPane.showMessageDialog(this, "Please wait for the current operation to finish.");
            return;
        }
        session.logout();
        navigation.showLogin();
    }

    @Override public void dispose() {
        session.logout();
        super.dispose();
    }
}
