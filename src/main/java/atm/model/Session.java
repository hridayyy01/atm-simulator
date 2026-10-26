package atm.model;

import java.util.Objects;

/** Identity for one authenticated desktop session; never contains a PIN. */
public final class Session {
    private AuthenticatedUser user;

    public Session(AuthenticatedUser user) {
        this.user = Objects.requireNonNull(user);
        Objects.requireNonNull(user.accountId());
        Objects.requireNonNull(user.name());
    }

    public synchronized String getAccountId() {
        requireActive();
        return user.accountId();
    }

    public synchronized String getName() {
        requireActive();
        return user.name();
    }

    public synchronized boolean isActive() {
        return user != null;
    }

    public synchronized void requireActive() {
        if (!isActive()) throw new IllegalStateException("This session has ended. Please log in again.");
    }

    public synchronized void logout() {
        user = null;
    }
}
