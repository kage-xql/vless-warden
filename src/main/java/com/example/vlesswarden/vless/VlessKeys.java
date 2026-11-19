package com.example.vlesswarden.vless;

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;

import java.security.SecureRandom;
import java.util.Base64;

public final class VlessKeys {
    private static final SecureRandom RANDOM = new SecureRandom();

    private VlessKeys() {
    }

    public record KeyPair(String privateKey, String publicKey) {
    }

    public static KeyPair generateRealityKeyPair() {
        byte[] privateBytes = new byte[32];
        RANDOM.nextBytes(privateBytes);
        // Xray клэмпит скаляр именно так (см. genCurve25519 в Xray-core).
        privateBytes[0] &= (byte) 248;
        privateBytes[31] &= (byte) 127;
        privateBytes[31] |= (byte) 64;
        X25519PrivateKeyParameters privateKey = new X25519PrivateKeyParameters(privateBytes, 0);
        byte[] publicBytes = privateKey.generatePublicKey().getEncoded();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return new KeyPair(encoder.encodeToString(privateBytes), encoder.encodeToString(publicBytes));
    }

    public static String shortId() {
        byte[] value = new byte[8];
        RANDOM.nextBytes(value);
        return java.util.HexFormat.of().formatHex(value);
    }
}
