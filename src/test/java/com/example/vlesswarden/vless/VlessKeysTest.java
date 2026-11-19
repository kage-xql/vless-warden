package com.example.vlesswarden.vless;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VlessKeysTest {

    @Test
    void realityKeyPairIsUrlSafeBase64Of32Bytes() {
        VlessKeys.KeyPair keyPair = VlessKeys.generateRealityKeyPair();

        Base64.Decoder decoder = Base64.getUrlDecoder();
        assertEquals(32, decoder.decode(keyPair.privateKey()).length);
        assertEquals(32, decoder.decode(keyPair.publicKey()).length);
        assertNotEquals(keyPair.privateKey(), keyPair.publicKey());
    }

    @Test
    void shortIdIsSixteenHexCharacters() {
        String shortId = VlessKeys.shortId();

        assertTrue(shortId.matches("[0-9a-f]{16}"));
    }
}
