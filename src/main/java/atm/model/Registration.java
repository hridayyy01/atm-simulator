package atm.model;

import java.security.SecureRandom;
import java.util.Objects;

/** One in-memory application shared by all signup screens. */
public final class Registration {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private String applicationId = newApplicationId();
    private PersonalDetails personalDetails;
    private AdditionalDetails additionalDetails;
    private CardCredentials credentials;
    private boolean outcomeUnknown;

    public synchronized String getApplicationId() {
        return applicationId;
    }

    public synchronized PersonalDetails getPersonalDetails() {
        return personalDetails;
    }

    public synchronized void setPersonalDetails(PersonalDetails details) {
        requireEditable();
        personalDetails = Objects.requireNonNull(details);
    }

    public synchronized AdditionalDetails getAdditionalDetails() {
        return additionalDetails;
    }

    public synchronized void setAdditionalDetails(AdditionalDetails details) {
        requireEditable();
        additionalDetails = Objects.requireNonNull(details);
    }

    public synchronized CardCredentials getCredentials() {
        return credentials;
    }

    public synchronized boolean isSubmitted() {
        return credentials != null;
    }

    public synchronized boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }

    public synchronized void renewApplicationId() {
        requireEditable();
        applicationId = newApplicationId();
    }

    public synchronized void markSubmitted(CardCredentials credentials) {
        requireEditable();
        this.credentials = Objects.requireNonNull(credentials);
    }

    public synchronized void markOutcomeUnknown() {
        outcomeUnknown = true;
    }

    private void requireEditable() {
        if (isSubmitted() || outcomeUnknown) {
            throw new IllegalStateException("This application can no longer be changed or resubmitted.");
        }
    }

    private static String newApplicationId() {
        // 100 random bits, case-insensitive and within the existing VARCHAR(20).
        StringBuilder id = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }
}
