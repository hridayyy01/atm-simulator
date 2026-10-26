package atm.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record Transaction(long transactionId, String accountId, TransactionType type,
                          BigDecimal amount, Instant occurredAt) {
    public Transaction {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(occurredAt);
        amount = Money.normalize(amount);
        if (amount.signum() <= 0) throw new IllegalArgumentException("Transaction amount must be positive.");
        if (occurredAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("Timestamp must be exact to microseconds.");
        }
    }
}
