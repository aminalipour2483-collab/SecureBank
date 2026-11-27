package com.bank.util;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Utility class for hashing and verifying PINs/Passwords using BCrypt.
 */
public class BCryptUtil {

    // Hash a plain-text secret
    public static String hash(String plainText) {
        // 12 is the cost factor (work factor) — strong security with acceptable speed.
        return BCrypt.hashpw(plainText, BCrypt.gensalt(12));
    }

    /** Returns true when the stored value looks like a jBCrypt hash. */
    public static boolean isBcryptHash(String value) {
        return value != null && value.startsWith("$2a$") && value.length() == 60;
    }

    // Compare a plain-text secret with a hash stored in the database.
    public static boolean verify(String plainText, String hashed) {
        if (plainText == null || !isBcryptHash(hashed)) {
            return false;
        }
        try {
            return BCrypt.checkpw(plainText, hashed);
        } catch (IllegalArgumentException malformedHash) {
            return false;
        }
    }
}
