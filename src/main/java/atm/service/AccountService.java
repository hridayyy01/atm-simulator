package atm.service;

import atm.dao.AccountDao;
import atm.dao.TransactionDao;
import atm.db.Conn;
import atm.model.Account;
import atm.model.Money;
import atm.model.Session;
import atm.model.Transaction;
import atm.model.TransactionType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

public class AccountService {
    private final AccountDao accountDao;
    private final TransactionDao transactionDao;

    public AccountService(AccountDao accountDao, TransactionDao transactionDao) {
        this.accountDao = Objects.requireNonNull(accountDao);
        this.transactionDao = Objects.requireNonNull(transactionDao);
    }

    public Account deposit(Session session, BigDecimal amount) throws SQLException {
        return post(session, amount, TransactionType.DEPOSIT);
    }

    public Account withdraw(Session session, BigDecimal amount) throws SQLException {
        return post(session, amount, TransactionType.WITHDRAWAL);
    }

    public Account getAccount(Session session) throws SQLException {
        Account account = accountDao.findForSession(session);
        if (account == null) throw new AccountOperationException(AccountOperationException.Reason.ACCOUNT_NOT_FOUND);
        return account;
    }

    public List<Transaction> getRecentTransactions(Session session) throws SQLException {
        getAccount(session);
        return transactionDao.findRecentForSession(session);
    }

    private Account post(Session session, BigDecimal amount, TransactionType type) throws SQLException {
        String accountId = session.getAccountId();
        if (amount == null) throw new IllegalArgumentException("Amount is required.");
        amount = Money.normalize(amount);
        if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive.");

        Account result = null;
        boolean committed = false;
        try (Connection con = Conn.getConnection()) {
            con.setAutoCommit(false);
            try {
                // Every balance-dependent decision uses the row locked by this transaction.
                Account current = accountDao.findByIdForUpdate(con, session);
                if (current == null) throw new AccountOperationException(AccountOperationException.Reason.ACCOUNT_NOT_FOUND);
                if (!current.balanceVerified()) {
                    throw new AccountOperationException(AccountOperationException.Reason.BALANCE_UNVERIFIED);
                }
                if (type == TransactionType.WITHDRAWAL && amount.compareTo(current.balance()) > 0) {
                    throw new AccountOperationException(AccountOperationException.Reason.INSUFFICIENT_FUNDS);
                }
                BigDecimal balance;
                try {
                    balance = Money.normalize(type == TransactionType.DEPOSIT
                            ? current.balance().add(amount) : current.balance().subtract(amount));
                } catch (IllegalArgumentException e) {
                    throw new AccountOperationException(AccountOperationException.Reason.BALANCE_LIMIT);
                }
                accountDao.updateBalance(con, session, balance);
                transactionDao.insert(con, session, type, amount, Instant.now().truncatedTo(ChronoUnit.MICROS));
                result = new Account(accountId, current.formno(), balance, true);
                con.commit();
                committed = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                try { con.rollback(); }
                catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException e) {
            // An acknowledged commit stays successful even if connection cleanup fails.
            if (committed) return result;
            throw e;
        }
    }
}
