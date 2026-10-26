package atm.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class Money {
    public static final BigDecimal ZERO = new BigDecimal("0.00");

    private Money() { }

    /** Exact DECIMAL(19,2) conversion; never rounds or uses floating point. */
    public static BigDecimal normalize(BigDecimal value) {
        Objects.requireNonNull(value, "Monetary value is required.");
        final BigDecimal scaled;
        try {
            scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Monetary values must be exact to two decimal places.");
        }
        if (scaled.precision() > 19) {
            throw new IllegalArgumentException("Monetary value exceeds DECIMAL(19,2).");
        }
        return scaled;
    }
}
