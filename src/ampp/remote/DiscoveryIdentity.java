package ampp.remote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class DiscoveryIdentity {
    static String proof(String token, String nonce, String node, String authority) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(token.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            byte[] digest = mac.doFinal(("ampp-discovery-proof-v1\n" + nonce + "\n" + node + "\n" + authority)
                    .getBytes(StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.GeneralSecurityException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("ampp-lan-discovery-v1:" + token).getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static boolean accepts(String pinned, String advertised, String authenticated) {
        return advertised != null && advertised.matches("[0-9a-f-]{36}")
                && advertised.equals(authenticated) && (pinned.isEmpty() || pinned.equals(authenticated));
    }
}
