package atm.ui;

import atm.model.Transaction;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Renders authorized query results in their database order, without editable cells. */
public final class MiniStatementPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT).withZone(ZoneOffset.UTC);

    public MiniStatementPanel(List<Transaction> transactions) {
        super(new BorderLayout());
        if (transactions.isEmpty()) {
            add(new JLabel("No transactions yet for this account."));
            return;
        }
        Object[][] rows = new Object[transactions.size()][4];
        for (int i = 0; i < transactions.size(); i++) {
            Transaction transaction = transactions.get(i);
            rows[i] = new Object[]{transaction.transactionId(), TIME.format(transaction.occurredAt()),
                    transaction.type().name(), CurrencyFormatter.format(transaction.amount())};
        }
        DefaultTableModel model = new DefaultTableModel(rows, new String[]{"Transaction ID", "Time (UTC)", "Type", "Amount"}) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
            @Override public Class<?> getColumnClass(int column) { return column == 0 ? Long.class : String.class; }
        };
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {140, 220, 120, 220};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        DefaultTableCellRenderer rightAligned = new DefaultTableCellRenderer();
        rightAligned.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(3).setCellRenderer(rightAligned);
        table.setPreferredScrollableViewportSize(new Dimension(700, 220));
        add(new JScrollPane(table));
    }
}
