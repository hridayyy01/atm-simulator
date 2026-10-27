package atm.ui;

import atm.model.Transaction;
import atm.model.TransactionType;
import java.awt.Component;
import java.awt.Container;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/** Presentation checks using headless Swing components, without a JFrame or database. */
public final class AccountViewsTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Locale originalLocale = Locale.getDefault();
            Locale originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
            TimeZone originalZone = TimeZone.getDefault();
            try {
                currencyFormatting();
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
                emptyHistory();
                statementPresentation();
            } finally {
                Locale.setDefault(originalLocale);
                Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale);
                TimeZone.setDefault(originalZone);
            }
        });
        System.out.println("PASS: locale-independent INR formatting, exact decimals, empty history and read-only UTC statement presentation.");
    }

    private static void currencyFormatting() {
        for (Locale locale : List.of(Locale.US, Locale.FRANCE, Locale.GERMANY, Locale.forLanguageTag("ar-EG"))) {
            Locale.setDefault(locale);
            check(CurrencyFormatter.format(new BigDecimal("0")).equals("INR 0.00"), "Show zero with two currency digits");
            check(CurrencyFormatter.format(new BigDecimal("1.2")).equals("INR 1.20"), "Always show both fractional digits");
            check(CurrencyFormatter.format(new BigDecimal("0.01")).equals("INR 0.01"), "Preserve small exact amounts");
            check(CurrencyFormatter.format(new BigDecimal("1234.5000")).equals("INR 1,234.50"), "Normalize trailing zeros without changing value");
            check(CurrencyFormatter.format(new BigDecimal("9007199254740993.01")).equals("INR 9,007,199,254,740,993.01"),
                    "Format large amounts without floating-point precision loss");
            check(CurrencyFormatter.format(new BigDecimal("99999999999999999.99")).equals("INR 99,999,999,999,999,999.99"),
                    "Preserve the DECIMAL(19,2) upper boundary");
        }
        for (String invalid : List.of("0.001", "1234.567", "100000000000000000.00")) {
            try {
                CurrencyFormatter.format(new BigDecimal(invalid));
                throw new AssertionError("Reject an amount that would require rounding or exceed the supported range");
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static void emptyHistory() {
        MiniStatementPanel panel = new MiniStatementPanel(List.of());
        JLabel message = find(panel, JLabel.class);
        check(message != null && message.getText().equals("No transactions yet for this account."), "Show an explicit account-history empty state");
        check(find(panel, JTable.class) == null, "Do not show an unexplained empty table");
    }

    private static void statementPresentation() {
        List<Transaction> transactions = List.of(
                transaction(500, "1000.5", TransactionType.DEPOSIT, "2026-10-27T10:00:01.000002Z"),
                transaction(202, "0.01", TransactionType.WITHDRAWAL, "2026-10-27T10:00:00.123456Z"),
                transaction(201, "0.10", TransactionType.DEPOSIT, "2026-10-27T10:00:00.123456Z"),
                transaction(900, "9007199254740993.01", TransactionType.DEPOSIT, "2026-10-27T09:59:59Z"));
        JTable table = find(new MiniStatementPanel(transactions), JTable.class);
        check(table != null && table.getRowCount() == 4 && table.getColumnCount() == 4, "Show all supplied statement records and fields");
        String[] columns = {"Transaction ID", "Time (UTC)", "Type", "Amount"};
        for (int column = 0; column < columns.length; column++) {
            check(table.getColumnName(column).equals(columns[column]), "Identify every statement field");
        }
        long[] ids = {500, 202, 201, 900};
        for (int row = 0; row < ids.length; row++) {
            check(table.getValueAt(row, 0).equals(ids[row]), "Preserve timestamp/ID query ordering rather than resorting by ID alone");
            for (int column = 0; column < table.getColumnCount(); column++) {
                check(!table.isCellEditable(row, column), "Keep statement history read-only");
            }
        }
        check(table.getValueAt(0, 1).equals("2026-10-27 10:00:01.000002"), "Show UTC microseconds independent of the system timezone");
        check(table.getValueAt(1, 1).equals("2026-10-27 10:00:00.123456"), "Preserve fractional timestamp precision");
        check(table.getValueAt(3, 1).equals("2026-10-27 09:59:59.000000"), "Use the same timestamp precision for whole seconds");
        check(table.getValueAt(1, 2).equals("WITHDRAWAL"), "Display the transaction type");
        check(table.getValueAt(0, 3).equals("INR 1,000.50") && table.getValueAt(1, 3).equals("INR 0.01")
                && table.getValueAt(3, 3).equals("INR 9,007,199,254,740,993.01"), "Use the shared currency convention throughout statements");
    }

    private static Transaction transaction(long id, String amount, TransactionType type, String occurredAt) {
        return new Transaction(id, "test-account", type, new BigDecimal(amount), Instant.parse(occurredAt));
    }

    private static <T extends Component> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T result = find(child, type);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
