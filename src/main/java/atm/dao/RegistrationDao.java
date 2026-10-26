package atm.dao;

import atm.db.Conn;
import atm.model.AdditionalDetails;
import atm.model.CardCredentials;
import atm.model.PersonalDetails;
import atm.model.Registration;
import atm.model.Money;
import atm.security.PinHasher;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;

public class RegistrationDao {
    private static final int MAX_ATTEMPTS = 5;
    private final SecureRandom random = new SecureRandom();

    /** Saves the entire application once; repeated calls return the committed credentials. */
    public CardCredentials submit(Registration registration) throws SQLException {
        // The same draft may be passed to multiple screens or concurrent callers.
        synchronized (registration) {
            if (registration.isSubmitted()) {
                return registration.getCredentials();
            }
            if (registration.isOutcomeUnknown()) {
                throw new SQLException("The previous submission outcome is unknown. Check application "
                        + registration.getApplicationId() + " before submitting again.");
            }
            if (registration.getPersonalDetails() == null || registration.getAdditionalDetails() == null) {
                throw new IllegalStateException("Complete both signup pages before submitting the application.");
            }

            try (Connection con = Conn.getConnection()) {
                con.setAutoCommit(false);
                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                    Stage stage = Stage.PERSONAL;
                    CardCredentials credentials = null;
                    try {
                        insertPersonalDetails(con, registration);
                        stage = Stage.ADDITIONAL;
                        insertAdditionalDetails(con, registration);
                        stage = Stage.ACCOUNT;
                        insertAccount(con, registration);
                        stage = Stage.CREDENTIALS;
                        credentials = generateCredentials();
                        insertCredentials(con, registration.getApplicationId(), credentials);
                        stage = Stage.COMMIT;
                        con.commit();
                        registration.markSubmitted(credentials);
                        return credentials;
                    } catch (SQLException | RuntimeException e) {
                        if (stage == Stage.COMMIT) {
                            // A lost commit acknowledgement must never create another account.
                            registration.markOutcomeUnknown();
                        }
                        try {
                            con.rollback();
                        } catch (SQLException rollbackError) {
                            registration.markOutcomeUnknown();
                            e.addSuppressed(rollbackError);
                            throw new SQLException("Registration outcome could not be confirmed for application "
                                    + registration.getApplicationId() + ". Check it before retrying.", e);
                        }
                        if (registration.isOutcomeUnknown()) {
                            throw new SQLException("Registration commit could not be confirmed for application "
                                    + registration.getApplicationId() + ". Check it before retrying.", e);
                        }
                        if (e instanceof SQLException sqlError && isDuplicateKey(sqlError)
                                && (stage == Stage.PERSONAL || stage == Stage.ACCOUNT || stage == Stage.CREDENTIALS)) {
                            if (attempt == MAX_ATTEMPTS) {
                                throw new SQLException("Unable to allocate a unique application, account ID or card after "
                                        + MAX_ATTEMPTS + " attempts. No registration was saved.", sqlError);
                            }
                            if (stage == Stage.PERSONAL) {
                                registration.renewApplicationId();
                            } else if (stage == Stage.ACCOUNT) {
                                registration.renewAccountId();
                            }
                            continue;
                        }
                        throw e;
                    } finally {
                        if (credentials != null && !registration.isSubmitted()) credentials.clearPin();
                    }
                }
                throw new AssertionError("Registration retry limit was not enforced");
            } catch (SQLException e) {
                // Connection cleanup cannot turn an acknowledged commit into a failed submission.
                if (registration.isSubmitted()) {
                    return registration.getCredentials();
                }
                throw e;
            }
        }
    }

    private void insertPersonalDetails(Connection con, Registration registration) throws SQLException {
        PersonalDetails details = registration.getPersonalDetails();
        String sql = "INSERT INTO signup (formno, name, fname, gender, email, marital, address, city, pincode, state)" + "VALUES ( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, registration.getApplicationId());
            ps.setString(2, details.name());
            ps.setString(3, details.fatherName());
            ps.setString(4, details.gender());
            ps.setString(5, details.email());
            ps.setString(6, details.maritalStatus());
            ps.setString(7, details.address());
            ps.setString(8, details.city());
            ps.setString(9, details.pincode());
            ps.setString(10, details.state());
            requireOneRow(ps.executeUpdate());
        }
    }

    private void insertAdditionalDetails(Connection con, Registration registration) throws SQLException {
        AdditionalDetails details = registration.getAdditionalDetails();
        String sql = "INSERT INTO signuptwo(formno,religion,category,income,education,occupation,pan,aadhar,isSenior)" + "VALUES( ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, registration.getApplicationId());
            ps.setString(2, details.religion());
            ps.setString(3, details.category());
            ps.setString(4, details.income());
            ps.setString(5, details.education());
            ps.setString(6, details.occupation());
            ps.setString(7, details.pan());
            ps.setString(8, details.aadhar());
            ps.setString(9, details.senior());
            requireOneRow(ps.executeUpdate());
        }
    }

    private void insertAccount(Connection con, Registration registration) throws SQLException {
        String sql = "INSERT INTO accounts (account_id, formno, balance, balance_verified) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, registration.getAccountId());
            ps.setString(2, registration.getApplicationId());
            ps.setBigDecimal(3, Money.ZERO);
            ps.setBoolean(4, true);
            requireOneRow(ps.executeUpdate());
        }
    }

    private void insertCredentials(Connection con, String applicationId, CardCredentials credentials) throws SQLException {
        String sql = "INSERT INTO login (cardno, pin_hash, formno) VALUES (?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, credentials.getCardno());
            char[] pin = credentials.getPin();
            try {
                ps.setString(2, PinHasher.hash(pin));
            } finally {
                Arrays.fill(pin, '\0');
            }
            ps.setString(3, applicationId);
            // The primary key, rather than a racy SELECT, enforces card uniqueness.
            requireOneRow(ps.executeUpdate());
        }
    }

    private CardCredentials generateCredentials() {
        StringBuilder cardno = new StringBuilder(16);
        for (int i = 0; i < 16; i++) cardno.append(random.nextInt(10));
        int value = 1000 + random.nextInt(9000);
        char[] pin = new char[4];
        for (int i = pin.length - 1; i >= 0; i--) {
            pin[i] = (char) ('0' + value % 10);
            value /= 10;
        }
        try {
            return new CardCredentials(cardno.toString(), pin);
        } finally {
            Arrays.fill(pin, '\0');
        }
    }

    private boolean isDuplicateKey(SQLException error) {
        // MySQL ER_DUP_ENTRY. Other integrity violations must not be retried.
        return error.getErrorCode() == 1062;
    }

    private void requireOneRow(int rows) throws SQLException {
        if (rows != 1) {
            throw new SQLException("Registration insert failed; expected exactly one row.");
        }
    }

    private enum Stage { PERSONAL, ADDITIONAL, ACCOUNT, CREDENTIALS, COMMIT }
}
