package atm.security;

import atm.model.CardCredentials;
import java.util.Arrays;

public final class PinHasherTest {
    // Independently derived with Python hashlib.pbkdf2_hmac; dummy test PIN only.
    private static final String FIXTURE = "pbkdf2-sha256$600000$AAECAwQFBgcICQoLDA0ODw==$mj8Uk1lF7SLS70PvzppZDceQEIBGQtufTiYwfc03sp4=";

    public static void main(String[] args) {
        char[] pin = {'0', '0', '4', '2'};
        try {
            check(PinHasher.verify(pin, FIXTURE), "Verify an independently derived hash including leading zeros");
            check(!PinHasher.verify(new char[]{'0', '0', '4', '3'}, FIXTURE), "Reject an incorrect PIN");
            String first = PinHasher.hash(pin);
            String second = PinHasher.hash(pin);
            check(!first.equals(second), "Use a fresh salt for each hash of the same PIN");
            check(PinHasher.verify(pin, first) && PinHasher.verify(pin, second), "Verify freshly generated hashes");
            check(Arrays.equals(pin, new char[]{'0', '0', '4', '2'}), "Do not mutate caller-owned PIN buffers");
            String[] parts = FIXTURE.split("\\$");
            String[] malformed = {null, "0042", "", FIXTURE + "$extra",
                    "sha256$600000$" + parts[2] + "$" + parts[3],
                    "pbkdf2-sha256$0$" + parts[2] + "$" + parts[3],
                    "pbkdf2-sha256$2147483647$" + parts[2] + "$" + parts[3],
                    "pbkdf2-sha256$600000$invalid!$" + parts[3],
                    "pbkdf2-sha256$600000$AA==$" + parts[3],
                    "pbkdf2-sha256$600000$" + parts[2] + "$AA=="};
            for (String encoded : malformed) {
                check(!PinHasher.verify(pin, encoded), "Reject plaintext and malformed or unsupported hashes");
            }
            for (char[] invalid : new char[][]{null, {}, {'1', '2', '3'}, {'1', '2', '3', '4', '5'}, {'1', '2', 'a', '4'}}) {
                check(!PinHasher.verify(invalid, FIXTURE), "Reject invalid PINs");
                try {
                    PinHasher.hash(invalid);
                    throw new AssertionError("Reject hashing an invalid PIN");
                } catch (IllegalArgumentException expected) { }
            }
            CardCredentials credentials = new CardCredentials("0000000000000001", pin);
            char[] copy = credentials.getPin();
            Arrays.fill(copy, '\0');
            char[] anotherCopy = credentials.getPin();
            try {
                check(Arrays.equals(pin, anotherCopy), "Protect the initial PIN from accidental mutation");
                check(!credentials.toString().contains(new String(pin)), "Redact credentials when converted to text");
            } finally {
                Arrays.fill(anotherCopy, '\0');
                credentials.clearPin();
            }
            credentials.clearPin();
            try {
                credentials.getPin();
                throw new AssertionError("Do not retain an initial PIN after display");
            } catch (IllegalStateException expected) { }
        } finally {
            Arrays.fill(pin, '\0');
        }
        System.out.println("PASS: PBKDF2 fixture, random salts, incorrect PINs, malformed hashes and secret clearing.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
