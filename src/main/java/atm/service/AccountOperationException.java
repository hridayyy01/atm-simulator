package atm.service;

/** Business outcomes that the UI can explain without exposing driver errors. */
public final class AccountOperationException extends IllegalStateException {
    public enum Reason { ACCOUNT_NOT_FOUND, BALANCE_UNVERIFIED, INSUFFICIENT_FUNDS, BALANCE_LIMIT }

    private final Reason reason;

    public AccountOperationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
