package atm.tools;

import atm.model.Account;
import atm.model.Money;
import atm.model.Transaction;
import atm.model.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

public final class AccountMigrationTest {
    public static void main(String[] args) {
        check(MigrateAccounts.parseAmount("9007199254740993.01").equals(new BigDecimal("9007199254740993.01")),
                "Keep amounts beyond floating-point integer precision exact");
        check(MigrateAccounts.parseAmount(" 0.10 ").add(MigrateAccounts.parseAmount("0.20")).equals(new BigDecimal("0.30")),
                "Preserve decimal arithmetic exactly");
        check(MigrateAccounts.parseAmount("1.000").equals(new BigDecimal("1.00")), "Losslessly remove trailing zeros");
        check(MigrateAccounts.parseAmount("99999999999999999.99").precision() == 19, "Accept the DECIMAL boundary");
        for (String invalid : new String[]{null, "", "0", "-1", "1.005", "1,000.00", "USD 10", "1e3", "100000000000000000.00"}) {
            try { MigrateAccounts.parseAmount(invalid); throw new AssertionError("Reject invalid amounts"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().equals("INVALID_AMOUNT"), "Report the amount problem"); }
        }
        check(MigrateAccounts.parseType(" deposit ") == TransactionType.DEPOSIT, "Normalize legacy deposits");
        check(MigrateAccounts.parseType("Withdraw") == TransactionType.WITHDRAWAL, "Normalize the documented legacy withdrawal alias");
        for (String invalid : new String[]{null, "", "transfer", "unknown", "credit"}) {
            try { MigrateAccounts.parseType(invalid); throw new AssertionError("Reject unsupported types"); }
            catch (IllegalArgumentException expected) { }
        }
        check(MigrateAccounts.parseTimestamp("2026-10-26T12:00:00+05:30").equals(Instant.parse("2026-10-26T06:30:00Z")),
                "Normalize explicit offsets to UTC");
        check(MigrateAccounts.parseTimestamp("2026-10-26T12:00:00.123456Z").getNano() == 123456000,
                "Preserve microseconds");
        for (String invalid : new String[]{null, "", "2026-10-26 12:00:00", "26/10/2026", "2026-02-30T12:00:00Z",
                "Mon Oct 26 12:00:00 IST 2026", "2026-10-26T12:00:00.123456789Z", "0001-01-01T00:00:00Z"}) {
            try { MigrateAccounts.parseTimestamp(invalid); throw new AssertionError("Report ambiguous or lossy timestamps"); }
            catch (IllegalArgumentException expected) { }
        }
        Account account = new Account("stable-account", "application", new BigDecimal("1.2300"), false);
        check(account.balance().equals(new BigDecimal("1.23")) && !account.balanceVerified(), "Preserve explicit reconciliation state");
        try { new Account("account", "form", new BigDecimal("-0.01"), true); throw new AssertionError("Reject negative balances"); }
        catch (IllegalArgumentException expected) { }
        try { Money.normalize(new BigDecimal("0.001")); throw new AssertionError("Never round a monetary value"); }
        catch (IllegalArgumentException expected) { }
        try { new Transaction(1, "account", TransactionType.DEPOSIT, Money.ZERO, Instant.now()); throw new AssertionError("Reject zero transactions"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("PASS: exact BigDecimal amounts, range/precision limits, UTC conversion and ambiguous legacy values.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
