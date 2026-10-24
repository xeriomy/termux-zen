package com.termux.app.plugins;

import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * Verifies the repository index with a pinned RSA key. RSA + SHA256withRSA is used because
 * Ed25519 is only available from API 33, while this app supports API 21.
 */
public final class PluginSignatureVerifier {

    private static final int MIN_RSA_BITS = 2048;

    private PluginSignatureVerifier() {
    }

    public static boolean verify(byte[] data, byte[] signature, String base64PublicKey)
        throws PluginException {
        if (base64PublicKey == null || base64PublicKey.trim().isEmpty())
            throw new PluginException("Repository public key is not configured");
        if (signature == null || signature.length == 0)
            throw new PluginException("Repository signature is empty");

        PublicKey publicKey;
        try {
            byte[] keyBytes = decodeBase64(base64PublicKey);
            publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new PluginException("Repository public key is invalid", e);
        }
        if (!(publicKey instanceof RSAPublicKey)
            || ((RSAPublicKey) publicKey).getModulus().bitLength() < MIN_RSA_BITS)
            throw new PluginException("Repository RSA key must be at least " + MIN_RSA_BITS + " bits");

        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /** Standard Base64, no padding required to be sensible; whitespace is ignored. */
    public static byte[] decodeBase64(String value) throws PluginException {
        if (value == null) throw new PluginException("Missing Base64 value");
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c)) cleaned.append(c);
        }
        if (cleaned.length() % 4 != 0) throw new PluginException("Invalid Base64 length");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] block = new int[4];
        for (int i = 0; i < cleaned.length(); i += 4) {
            int padding = 0;
            for (int j = 0; j < 4; j++) {
                char c = cleaned.charAt(i + j);
                if (c == '=') {
                    if (i + 4 != cleaned.length() || j < 2)
                        throw new PluginException("Invalid Base64 padding");
                    block[j] = 0;
                    padding++;
                } else {
                    if (padding > 0) throw new PluginException("Invalid Base64 padding");
                    block[j] = base64Value(c);
                }
            }
            out.write((block[0] << 2) | (block[1] >> 4));
            if (padding < 2) out.write(((block[1] & 0x0f) << 4) | (block[2] >> 2));
            if (padding < 1) out.write(((block[2] & 0x03) << 6) | block[3]);
        }
        return out.toByteArray();
    }

    private static int base64Value(char c) throws PluginException {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        throw new PluginException("Invalid Base64 character");
    }

}
