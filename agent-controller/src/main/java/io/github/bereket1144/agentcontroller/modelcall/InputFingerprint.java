package io.github.bereket1144.agentcontroller.modelcall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The fingerprint of what is sent to a model: a SHA-256 hash, as 64 hexadecimal characters, of the profile name, the
 * system text and the user text. It uses no run id and no time, so equal prompts sent to the same profile have equal
 * fingerprints.
 */
public final class InputFingerprint {

    private InputFingerprint() {
    }

    public static String of(String profileName, String systemText, String userText) {
        return hash("SHA-256", lengthPrefixed(profileName) + lengthPrefixed(systemText) + lengthPrefixed(userText));
    }

    /** Each part is written as its length, a colon and the part, so text moved between parts changes the hash. */
    private static String lengthPrefixed(String part) {
        return part.length() + ":" + part;
    }

    /** Package-private so that a test can ask for an algorithm the JVM does not have. */
    static String hash(String algorithm, String text) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Hash algorithm not available: " + algorithm, exception);
        }
    }
}
