package com.oilquiz.app.security.totp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** RFC 6238 测试向量 + otpauth 解析回归。 */
public class TotpGeneratorTest {

    private static final String SHA1_SEED = "12345678901234567890";
    private static final String SHA1_B32 = TotpGenerator.asciiSecretToBase32(SHA1_SEED);
    private static final String SHA256_SEED = "12345678901234567890123456789012";
    private static final String SHA256_B32 = TotpGenerator.asciiSecretToBase32(SHA256_SEED);

    @Test
    public void base32MatchesRfcSeed() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", SHA1_B32);
        assertTrue(TotpGenerator.decodeBase32("gezd gnbv gy3t qojq gezd gnbv gy3t qojq").length
                == SHA1_SEED.length());
    }

    @Test
    public void rfc6238Sha1Vectors() {
        assertEquals("94287082", code(SHA1_B32, "SHA1", 8, 59));
        assertEquals("07081804", code(SHA1_B32, "SHA1", 8, 1111111109L));
        assertEquals("14050471", code(SHA1_B32, "SHA1", 8, 1111111111L));
        assertEquals("89005924", code(SHA1_B32, "SHA1", 8, 1234567890L));
        assertEquals("69279037", code(SHA1_B32, "SHA1", 8, 2000000000L));
        assertEquals("65353130", code(SHA1_B32, "SHA1", 8, 20000000000L));

        assertEquals("287082", code(SHA1_B32, "SHA1", 6, 59));
        assertEquals("081804", code(SHA1_B32, "SHA1", 6, 1111111109L));
        assertEquals("050471", code(SHA1_B32, "SHA1", 6, 1111111111L));
        assertEquals("005924", code(SHA1_B32, "SHA1", 6, 1234567890L));
        assertEquals("279037", code(SHA1_B32, "SHA1", 6, 2000000000L));
        assertEquals("353130", code(SHA1_B32, "SHA1", 6, 20000000000L));
    }

    @Test
    public void rfc6238Sha256Vectors() {
        assertEquals("46119246", code(SHA256_B32, "SHA256", 8, 59));
        assertEquals("68084774", code(SHA256_B32, "SHA256", 8, 1111111109L));
        assertEquals("67062674", code(SHA256_B32, "SHA256", 8, 1111111111L));
        assertEquals("91819424", code(SHA256_B32, "SHA256", 8, 1234567890L));
        assertEquals("90698825", code(SHA256_B32, "SHA256", 8, 2000000000L));
        assertEquals("77737706", code(SHA256_B32, "SHA256", 8, 20000000000L));
    }

    @Test
    public void countdownNeverZero() {
        assertEquals(30, TotpGenerator.remainingSeconds(30, 0));
        assertEquals(1, TotpGenerator.remainingSeconds(30, 29));
        assertEquals(30, TotpGenerator.remainingSeconds(30, 30));
        assertTrue(TotpGenerator.progress(30, 15) > 0f);
    }

    @Test
    public void parsesOtpauthUri() {
        String uri = "otpauth://totp/GitHub:alice@example.com"
                + "?secret=" + SHA1_B32
                + "&issuer=GitHub&algorithm=SHA256&digits=8&period=60";
        TotpAccount a = TotpUri.parse(uri);
        assertNotNull(a);
        assertEquals("GitHub", a.issuer);
        assertEquals("alice@example.com", a.account);
        assertEquals("SHA256", a.algorithm);
        assertEquals(8, a.digits);
        assertEquals(60, a.period);
        assertEquals(SHA1_B32, a.secret);
    }

    @Test
    public void rejectsNonTotpAndBadSecret() {
        assertThrows(IllegalArgumentException.class,
                () -> TotpUri.parse("https://example.com/"));
        assertThrows(IllegalArgumentException.class,
                () -> TotpUri.parse("otpauth://hotp/Test?secret=" + SHA1_B32 + "&counter=1"));
        assertThrows(IllegalArgumentException.class,
                () -> TotpUri.parse("otpauth://totp/Test?secret=not_base32!!"));
    }

    private static String code(String secret, String alg, int digits, long time) {
        return TotpGenerator.generate(secret, alg, digits, 30, time);
    }
}
