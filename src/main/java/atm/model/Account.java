package atm.model;

import java.math.BigDecimal;
import java.util.Objects;

/** An unverified legacy balance is a placeholder pending reconciliation. */
public record Account(String accountId, String formno, BigDecimal balance, boolean balanceVerified) {
    public Account {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(formno);
        balance = Money.normalize(balance);
        if (balance.signum() < 0) throw new IllegalArgumentException("Balance cannot be negative.");
    }
}
