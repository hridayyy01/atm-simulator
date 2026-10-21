package atm.model;

/** Distinguishes the existing card-issuance outcomes without UI dependencies. */
public record CardIssueResult(Status status, CardCredentials credentials) {
    public enum Status {
        INCOMPLETE_OR_ALREADY_ISSUED,
        ISSUED,
        INSERT_FAILED
    }
}
