package com.oilquiz.app.weather;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.concurrent.atomic.AtomicReference;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/**
 * QWeather JWT authentication using Ed25519 (EdDSA) signing.
 *
 * Generates short-lived JWT tokens for QWeather API authentication.
 * Tokens are cached and refreshed automatically before expiry.
 */
public class QWeatherJwtGenerator {

    private static final String TAG = "QWeatherJwtGenerator";
    private static final String ALGORITHM = "EdDSA";
    private static final String HEADER_JSON = "{\"alg\":\"EdDSA\",\"kid\":\"%s\"}";
    private static final long TOKEN_REFRESH_BUFFER_MS = 60_000; // refresh 60s before expiry

    private final String keyId;
    private final String projectId;
    private final PrivateKey privateKey;

    // Cached token
    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    public QWeatherJwtGenerator(String privateKeyPem, String projectId, String keyId) throws Exception {
        this.projectId = projectId;
        this.keyId = keyId;
        this.privateKey = parsePrivateKey(privateKeyPem);
    }

    /**
     * Generate a new Ed25519 key pair for QWeather JWT authentication.
     * Returns a KeyPairResult containing PEM-formatted private and public keys.
     *
     * Use the public key in QWeather console, keep the private key secret.
     */
    public static KeyPairResult generateKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(ALGORITHM);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        String privatePem = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        String publicPem = toPem("PUBLIC KEY", keyPair.getPublic().getEncoded());

        return new KeyPairResult(privatePem, publicPem);
    }

    /**
     * Convert key bytes to PEM format.
     */
    private static String toPem(String type, byte[] keyBytes) {
        String base64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append("\n");
        }
        sb.append("-----END ").append(type).append("-----");
        return sb.toString();
    }

    /**
     * Get a valid JWT token, refreshing if needed.
     */
    public String getToken() {
        CachedToken cached = cachedToken.get();
        if (cached != null && !cached.isExpired()) {
            return cached.token;
        }
        return refreshToken();
    }

    /**
     * Force refresh the JWT token.
     */
    public synchronized String refreshToken() {
        CachedToken cached = cachedToken.get();
        if (cached != null && !cached.isExpired()) {
            return cached.token;
        }

        try {
            long nowSeconds = System.currentTimeMillis() / 1000;
            long iat = nowSeconds - 30; // 30 seconds in the past to prevent clock skew
            long exp = iat + 900; // 15 minutes validity

            String token = generateJwt(iat, exp);
            long expiresAtMs = exp * 1000;
            cachedToken.set(new CachedToken(token, expiresAtMs));
            Log.d(TAG, "JWT token refreshed, expires in " + (exp - nowSeconds) + "s");
            return token;
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate JWT token", e);
            return null;
        }
    }

    /**
     * Generate a complete JWT string.
     */
    private String generateJwt(long iat, long exp) throws Exception {
        // Header
        String headerJson = String.format(HEADER_JSON, keyId);
        String headerEncoded = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));

        // Payload
        String payloadJson = String.format(
            "{\"sub\":\"%s\",\"iat\":%d,\"exp\":%d}",
            projectId, iat, exp
        );
        String payloadEncoded = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));

        // Data to sign
        String data = headerEncoded + "." + payloadEncoded;
        byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);

        // Ed25519 signature using BouncyCastle
        byte[] signatureBytes;
        if (privateKey instanceof Ed25519PrivateKeyWrapper) {
            Ed25519PrivateKeyWrapper wrapper = (Ed25519PrivateKeyWrapper) privateKey;
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(true, wrapper.getParams());
            signer.update(dataBytes, 0, dataBytes.length);
            signatureBytes = signer.generateSignature();
        } else {
            // Fallback to standard Signature API
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(dataBytes);
            signatureBytes = signature.sign();
        }

        String signatureEncoded = base64UrlEncode(signatureBytes);
        return data + "." + signatureEncoded;
    }

    /**
     * Parse PEM-encoded private key.
     */
    private PrivateKey parsePrivateKey(String privateKeyPem) throws Exception {
        String cleaned = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN ED25519 PRIVATE KEY-----", "")
            .replace("-----END ED25519 PRIVATE KEY-----", "")
            .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.decode(cleaned, Base64.DEFAULT);

        // Extract the 32-byte seed from PKCS8 encoding
        // Ed25519 PKCS8: 302e 0201 00300506032b6570 0422 0420 [32 bytes seed]
        byte[] seed = null;
        if (keyBytes.length == 48) {
            seed = new byte[32];
            System.arraycopy(keyBytes, 16, seed, 0, 32);
        } else {
            // Find the 04 20 pattern (OCTET STRING, 32 bytes)
            for (int i = 0; i < keyBytes.length - 32; i++) {
                if (keyBytes[i] == 0x04 && keyBytes[i + 1] == 0x20) {
                    seed = new byte[32];
                    System.arraycopy(keyBytes, i + 2, seed, 0, 32);
                    break;
                }
            }
        }

        if (seed == null) {
            throw new RuntimeException("Could not extract Ed25519 seed from PKCS8 key (length=" + keyBytes.length + ")");
        }

        Log.d(TAG, "Extracted Ed25519 seed (" + seed.length + " bytes)");

        // Use BouncyCastle to create the private key from seed
        Ed25519PrivateKeyParameters privateKeyParams = new Ed25519PrivateKeyParameters(seed, 0);

        // Create a wrapper PrivateKey that holds the BouncyCastle params
        // and can produce the signature via BouncyCastle
        return new Ed25519PrivateKeyWrapper(privateKeyParams);
    }

    /**
     * Wrapper class that holds Ed25519 private key parameters from BouncyCastle
     * and delegates signing to BouncyCastle's Ed25519Signer.
     */
    private static class Ed25519PrivateKeyWrapper implements PrivateKey {
        private final Ed25519PrivateKeyParameters params;

        Ed25519PrivateKeyWrapper(Ed25519PrivateKeyParameters params) {
            this.params = params;
        }

        public Ed25519PrivateKeyParameters getParams() {
            return params;
        }

        @Override
        public String getAlgorithm() { return "Ed25519"; }

        @Override
        public String getFormat() { return null; }

        @Override
        public byte[] getEncoded() { return null; }
    }

    /**
     * Base64URL encoding (RFC 7515).
     */
    private static String base64UrlEncode(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    public String getKeyId() {
        return keyId;
    }

    public String getProjectId() {
        return projectId;
    }

    /**
     * Result of Ed25519 key pair generation.
     */
    public static class KeyPairResult {
        public final String privateKeyPem;
        public final String publicKeyPem;

        public KeyPairResult(String privateKeyPem, String publicKeyPem) {
            this.privateKeyPem = privateKeyPem;
            this.publicKeyPem = publicKeyPem;
        }
    }

    private static class CachedToken {
        final String token;
        final long expiresAtMs;

        CachedToken(String token, long expiresAtMs) {
            this.token = token;
            this.expiresAtMs = expiresAtMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() >= expiresAtMs - TOKEN_REFRESH_BUFFER_MS;
        }
    }
}
