package atm.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class PinHasher {
    private static final String FORMAT = "pbkdf2-sha256";
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PinHasher() { }

    public static boolean isValidPin(char[] pin) {
        if (pin == null || pin.length != 4) return false;
        for (char digit : pin) {
            if (digit < '0' || digit > '9') return false;
        }
        return true;
    }

    public static String hash(char[] pin) {
        if (!isValidPin(pin)) throw new IllegalArgumentException("PIN must contain four digits.");
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(pin, salt);
        try {
            return FORMAT + "$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                    + "$" + Base64.getEncoder().encodeToString(derived);
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    /** Rejects plaintext, malformed encodings and unsupported parameters. */
    public static boolean verify(char[] pin, String encoded) {
        if (!isValidPin(pin) || encoded == null || encoded.length() > 255) return false;
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !FORMAT.equals(parts[0])
                || !Integer.toString(ITERATIONS).equals(parts[1])) return false;
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (salt.length != SALT_BYTES || expected.length != HASH_BYTES) return false;
        byte[] actual = derive(pin, salt);
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static byte[] derive(char[] pin, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, ITERATIONS, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Required PIN hashing algorithm is unavailable.");
        } finally {
            spec.clearPassword();
        }
    }
}
