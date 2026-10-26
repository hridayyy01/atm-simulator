package atm.model;

import java.util.Arrays;

public final class CardCredentials {
    private final String cardno;
    private char[] pin;

    public CardCredentials(String cardno, char[] pin) {
        this.cardno = cardno;
        this.pin = pin.clone();
    }

    public String getCardno() {
        return cardno;
    }

    public synchronized char[] getPin() {
        if (pin == null) throw new IllegalStateException("The initial PIN has already been cleared.");
        return pin.clone();
    }

    public synchronized void clearPin() {
        if (pin != null) {
            Arrays.fill(pin, '\0');
            pin = null;
        }
    }

    @Override
    public String toString() {
        return "CardCredentials[redacted]";
    }
}
