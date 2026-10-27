package atm.ui;

import atm.model.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class CurrencyFormatter {
    private CurrencyFormatter() { }

    /** One explicit currency and locale-independent decimal convention for the ATM. */
    public static String format(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setRoundingMode(RoundingMode.UNNECESSARY);
        return "INR " + format.format(Money.normalize(amount));
    }
}
