package com.termux.app.plugins;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginSignatureVerifierTest {

    @Test
    public void verifiesSignatureWithPinnedKey() throws Exception {
        KeyPair pair = keyPair(2048);
        byte[] index = "{\"schemaVersion\":1}".getBytes(PluginTestUtils.UTF_8);
        byte[] signature = sign(index, pair);
        assertTrue(PluginSignatureVerifier.verify(index, signature,
            PluginTestUtils.base64(pair.getPublic().getEncoded())));
    }

    @Test
    public void rejectsTamperedDataAndWrongKey() throws Exception {
        KeyPair pair = keyPair(2048);
        byte[] index = "signed index".getBytes(PluginTestUtils.UTF_8);
        byte[] signature = sign(index, pair);
        String key = PluginTestUtils.base64(pair.getPublic().getEncoded());

        assertFalse(PluginSignatureVerifier.verify("tampered".getBytes(PluginTestUtils.UTF_8),
            signature, key));

        KeyPair other = keyPair(2048);
        assertFalse(PluginSignatureVerifier.verify(index, signature,
            PluginTestUtils.base64(other.getPublic().getEncoded())));
    }

    @Test
    public void rejectsKeysBelow2048Bits() throws Exception {
        KeyPair weak = keyPair(1024);
        byte[] index = "index".getBytes(PluginTestUtils.UTF_8);
        assertThrows(PluginException.class, () -> PluginSignatureVerifier.verify(index,
            sign(index, weak), PluginTestUtils.base64(weak.getPublic().getEncoded())));
    }

    @Test
    public void decodesStandardBase64() throws Exception {
        byte[] value = new byte[]{0, 1, 2, 3, 4, (byte) 0xff};
        assertTrue(java.util.Arrays.equals(value,
            PluginSignatureVerifier.decodeBase64(PluginTestUtils.base64(value))));
    }

    private static KeyPair keyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static byte[] sign(byte[] data, KeyPair pair) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(data);
        return signer.sign();
    }

}
