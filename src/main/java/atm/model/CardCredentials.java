package atm.model;

public final class CardCredentials {
    private final String cardno;
    private final String pin;

    public CardCredentials(String cardno, String pin) {
        this.cardno = cardno;
        this.pin = pin;
    }

    public String getCardno() {
        return cardno;
    }

    public String getPin() {
        return pin;
    }
}
