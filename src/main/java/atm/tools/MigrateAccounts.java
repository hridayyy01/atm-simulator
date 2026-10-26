package atm.tools;

import atm.db.Conn;
import atm.model.Money;
import atm.model.TransactionType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Offline conversion after SQL migration 005. Never changes bank_legacy rows. */
public final class MigrateAccounts {
    private MigrateAccounts() { }

    public record Report(int accountsCreated, int transactionsImported, long recordsForReview) { }

    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("Use environment database configuration; this tool accepts no arguments.");
            System.exit(1);
        }
        try (Connection con = Conn.getConnection()) {
            Report report = migrate(con);
            System.out.println("Account migration complete: " + report.accountsCreated() + " accounts created, "
                    + report.transactionsImported() + " transactions imported, " + report.recordsForReview()
                    + " records require review. Run database/reports/legacy_account_migration.sql.");
        } catch (SQLException | RuntimeException e) {
            System.err.println("Account migration failed. No success was confirmed. Review the upgrade instructions before retrying.");
            System.exit(1);
        }
    }

    public static Report migrate(Connection con) throws SQLException {
        con.setAutoCommit(false);
        try {
            int created;
            int imported = 0;
            try (PreparedStatement issue = con.prepareStatement("INSERT INTO migration_issues "
                    + "(source_table, source_id, reason) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE reason = ?")) {
                created = createAccounts(con, issue);
                reportUnlinkedLogins(con, issue);
                String selectSql = "SELECT b.id, b.cardno, b.date, b.type, b.amount, "
                        + "t.transaction_id AS imported_id FROM bank_legacy b LEFT JOIN transactions t "
                        + "ON t.legacy_bank_id = b.id ORDER BY b.id";
                try (PreparedStatement select = con.prepareStatement(selectSql);
                     PreparedStatement lookup = con.prepareStatement("SELECT a.account_id FROM login l "
                             + "JOIN accounts a ON a.formno = l.formno WHERE l.cardno = ?");
                     PreparedStatement insert = con.prepareStatement("INSERT INTO transactions "
                             + "(account_id, transaction_type, amount, occurred_at, legacy_bank_id) VALUES (?, ?, ?, ?, ?)");
                     ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        if (rows.getString("imported_id") != null) continue;
                        long legacyId = rows.getLong("id");
                        List<String> reasons = new ArrayList<>();
                        String accountId = findAccount(lookup, rows.getString("cardno"));
                        if (accountId == null) reasons.add("UNRESOLVED_OR_AMBIGUOUS_ACCOUNT");
                        BigDecimal amount = null;
                        TransactionType type = null;
                        Instant occurredAt = null;
                        try { amount = parseAmount(rows.getString("amount")); }
                        catch (IllegalArgumentException e) { reasons.add(e.getMessage()); }
                        try { type = parseType(rows.getString("type")); }
                        catch (IllegalArgumentException e) { reasons.add(e.getMessage()); }
                        try { occurredAt = parseTimestamp(rows.getString("date")); }
                        catch (IllegalArgumentException e) { reasons.add(e.getMessage()); }
                        if (!reasons.isEmpty()) {
                            recordIssue(issue, "bank_legacy", Long.toString(legacyId), String.join(",", reasons));
                            continue;
                        }
                        insert.setString(1, accountId);
                        insert.setString(2, type.name());
                        insert.setBigDecimal(3, amount);
                        insert.setObject(4, LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
                        insert.setLong(5, legacyId);
                        requireOneRow(insert.executeUpdate());
                        imported++;
                    }
                }
            }
            long issues;
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) AS issue_count FROM migration_issues");
                 ResultSet rows = ps.executeQuery()) {
                rows.next();
                issues = rows.getLong("issue_count");
            }
            con.commit();
            return new Report(created, imported, issues);
        } catch (SQLException | RuntimeException e) {
            try { con.rollback(); }
            catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
            throw e;
        }
    }

    private static int createAccounts(Connection con, PreparedStatement issue) throws SQLException {
        List<String> forms = new ArrayList<>();
        String selectSql = "SELECT DISTINCT l.formno FROM login l JOIN signup s ON s.formno = l.formno "
                + "LEFT JOIN accounts a ON a.formno = l.formno WHERE a.account_id IS NULL";
        try (PreparedStatement ps = con.prepareStatement(selectSql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) forms.add(rs.getString("formno"));
        }
        try (PreparedStatement insert = con.prepareStatement("INSERT INTO accounts "
                + "(account_id, formno, balance, balance_verified) VALUES (?, ?, ?, ?)")) {
            for (String form : forms) {
                String accountId = UUID.randomUUID().toString();
                insert.setString(1, accountId);
                insert.setString(2, form);
                insert.setBigDecimal(3, Money.ZERO);
                insert.setBoolean(4, false);
                requireOneRow(insert.executeUpdate());
                recordIssue(issue, "accounts", accountId, "OPENING_BALANCE_UNVERIFIED");
            }
        }
        return forms.size();
    }

    private static void reportUnlinkedLogins(Connection con, PreparedStatement issue) throws SQLException {
        String sql = "SELECT l.cardno FROM login l LEFT JOIN signup s ON s.formno = l.formno WHERE s.formno IS NULL";
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rows = ps.executeQuery()) {
            while (rows.next()) recordIssue(issue, "login", rows.getString("cardno"), "UNRESOLVED_APPLICATION");
        }
    }

    private static String findAccount(PreparedStatement lookup, String cardno) throws SQLException {
        if (cardno == null || cardno.isBlank()) return null;
        lookup.setString(1, cardno);
        try (ResultSet rows = lookup.executeQuery()) {
            if (!rows.next()) return null;
            String id = rows.getString("account_id");
            return rows.next() ? null : id;
        }
    }

    private static void recordIssue(PreparedStatement ps, String table, String id, String reason) throws SQLException {
        ps.setString(1, table);
        ps.setString(2, id);
        ps.setString(3, reason);
        ps.setString(4, reason);
        ps.executeUpdate();
    }

    static BigDecimal parseAmount(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > 64 || !value.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw new IllegalArgumentException("INVALID_AMOUNT");
        }
        try {
            BigDecimal amount = Money.normalize(new BigDecimal(value));
            if (amount.signum() <= 0) throw new IllegalArgumentException();
            return amount;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("INVALID_AMOUNT");
        }
    }

    static TransactionType parseType(String raw) {
        return switch (raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)) {
            case "DEPOSIT" -> TransactionType.DEPOSIT;
            case "WITHDRAW", "WITHDRAWAL" -> TransactionType.WITHDRAWAL;
            default -> throw new IllegalArgumentException("UNSUPPORTED_TYPE");
        };
    }

    static Instant parseTimestamp(String raw) {
        try {
            Instant instant = OffsetDateTime.parse(raw == null ? "" : raw.trim()).toInstant();
            if (instant.getNano() % 1_000 != 0
                    || instant.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                    || instant.isAfter(Instant.parse("9999-12-31T23:59:59.499999Z"))) {
                throw new IllegalArgumentException("UNSUPPORTED_TIMESTAMP_PRECISION_OR_RANGE");
            }
            return instant;
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("AMBIGUOUS_OR_INVALID_TIMESTAMP");
        }
    }

    private static void requireOneRow(int count) throws SQLException {
        if (count != 1) throw new SQLException("Account migration write failed.");
    }
}
