package com.oilquiz.app.ai.util;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 密钥存储改造（旧 AES-CBC 固定 IV + 主密钥同存 → Keystore AES-256-GCM）的验证。
 *
 * <p><b>策略是"旧格式不做兼容"</b>：稳态读取只认 {@code enc:}，旧值要么被
 * {@link SecretMigrator} 一次性搬成 {@code enc:}，要么作废； {@link SecretCodec} 绝不把
 * 非 {@code enc:} 的值当明文返回。用内存 KV + 内存 AES-GCM 顶替 SharedPreferences 与
 * Android Keystore，因此这里跑的是<b>真密码学</b>（CBC/PKCS5 与 GCM 都真实加解密），
 * "能否解回同一份明文"这种问题只有真算法能证伪。</p>
 */
public class SecretMigratorTest {

    // ---------- 测试替身 ----------

    /** 内存 KV：CAS 与提交语义对齐 SharedPreferences 适配层。 */
    private static class MapKv implements SecretMigrator.SecretKv {
        final Map<String, String> data = new LinkedHashMap<>();
        int writes;
        int deletes;
        /** 每次 CAS 前按 key 回调：模拟"迁移期间用户又保存了新密钥"。 */
        ReplaceHook beforeReplace;

        interface ReplaceHook {
            void before(String key);
        }

        @Override
        public Set<String> keys() {
            return new LinkedHashSet<>(data.keySet());
        }

        @Override
        public String get(String key) {
            return data.get(key);
        }

        @Override
        public synchronized boolean replaceIfUnchanged(String key, String expectedCurrent,
                                                       String newValue) {
            if (beforeReplace != null) {
                beforeReplace.before(key);
            }
            String current = data.get(key);
            if (expectedCurrent == null ? current != null : !expectedCurrent.equals(current)) {
                return false;
            }
            data.put(key, newValue);
            writes++;
            return true;
        }

        @Override
        public void delete(String key) {
            if (data.remove(key) != null) deletes++;
        }

        @Override
        public void markMigrated(String flagKey) {
            data.put(flagKey, "true");
        }

        @Override
        public boolean isMigrated(String flagKey) {
            return "true".equals(data.get(flagKey));
        }
    }

    /** 内存 AES-256-GCM，落盘形态与 {@link ApiKeyCipher} 一致："enc:" + Base64(iv||ct)。 */
    private static class GcmStub implements SecretCodec.GcmCipher {
        private static final SecureRandom RANDOM = new SecureRandom();
        final byte[] key;
        boolean available = true;
        /** 置 true 模拟"失败时返回原文"（ApiKeyCipher 的不阻塞策略） */
        boolean silentlyReturnPlaintext;

        GcmStub() {
            try {
                KeyGenerator kg = KeyGenerator.getInstance("AES");
                kg.init(256);
                key = kg.generateKey().getEncoded();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String tryEncrypt(String plaintext) {
            if (!available) return null;
            if (silentlyReturnPlaintext) return plaintext;
            try {
                byte[] iv = new byte[12];
                RANDOM.nextBytes(iv);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, iv));
                byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
                byte[] combined = new byte[iv.length + ct.length];
                System.arraycopy(iv, 0, combined, 0, iv.length);
                System.arraycopy(ct, 0, combined, iv.length, ct.length);
                return SecretCodec.PREFIX_GCM
                        + java.util.Base64.getEncoder().encodeToString(combined);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public String tryDecrypt(String storedWithPrefix) {
            if (!available) return null;
            if (!storedWithPrefix.startsWith(SecretCodec.PREFIX_GCM)) {
                return storedWithPrefix; // 与 ApiKeyCipher 同：非本格式原样返回
            }
            try {
                byte[] combined = java.util.Base64.getDecoder()
                        .decode(storedWithPrefix.substring(SecretCodec.PREFIX_GCM.length()));
                byte[] iv = Arrays.copyOfRange(combined, 0, 12);
                byte[] ct = Arrays.copyOfRange(combined, 12, combined.length);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, iv));
                return new String(c.doFinal(ct), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public boolean isAvailable() {
            return available;
        }
    }

    // ---------- 旧格式夹具（复刻历史落盘形态） ----------

    private static final byte[] LEGACY_KEY = new byte[32];
    private static final byte[] LEGACY_IV = new byte[16];

    static {
        for (int i = 0; i < LEGACY_KEY.length; i++) LEGACY_KEY[i] = (byte) (i * 7 + 1);
        for (int i = 0; i < LEGACY_IV.length; i++) LEGACY_IV[i] = (byte) (200 - i);
    }

    /** 旧 APIKeyManager 的形态：AES/CBC/PKCS5 + 固定 IV + Android Base64.DEFAULT（折行 + 尾随换行）。 */
    private static String legacyCbc(String plaintext) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(LEGACY_KEY, "AES"),
                    new IvParameterSpec(LEGACY_IV));
            return SecretCodec.encodeBase64(
                    c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8))) + "\n";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return SecretCodec.encodeBase64(bytes) + "\n";
    }

    private static final SecretMigrator.LegacyCbcKey LEGACY =
            new SecretMigrator.LegacyCbcKey(b64(LEGACY_KEY), b64(LEGACY_IV));

    /** 与 {@code APIKeyManager.MIGRATION_SKIP_KEYS} 同集。 */
    private static final Set<String> SKIP_KEYS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("encryption_key", "iv_key", "active_api_id",
                    "api_archive_path", SecretMigrator.DEFAULT_FLAG_KEY)));

    private static final List<String> LEGACY_MATERIAL = Arrays.asList("encryption_key", "iv_key");

    private static MapKv kvWith(String... keyValuePairs) {
        MapKv kv = new MapKv();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            kv.data.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return kv;
    }

    private static SecretMigrator.Result run(MapKv kv, SecretCodec.GcmCipher gcm,
                                             SecretMigrator.LegacyCbcKey legacy) {
        return SecretMigrator.run(kv, gcm, legacy, SKIP_KEYS, LEGACY_MATERIAL,
                SecretMigrator.DEFAULT_FLAG_KEY);
    }

    // ---------- 一次性搬运：旧值升级后明文必须逐字节一致 ----------

    @Test
    public void legacyCbcValues_areUpgradedAndLegacyKeyIsDeleted() {
        String apiKey = "sk-live-1234567890abcdef";
        String host = "https://api.openai.com/v1";
        MapKv kv = kvWith(
                "encryption_key", b64(LEGACY_KEY),
                "iv_key", b64(LEGACY_IV),
                "openai", legacyCbc(apiKey),
                "openai_host", legacyCbc(host),
                "active_api_id", "cfg-1",
                "api_archive_path", "/storage/emulated/0/api_archive");

        GcmStub gcm = new GcmStub();
        SecretMigrator.Result r = run(kv, gcm, LEGACY);

        assertTrue("搬运应达成终态: " + r, r.complete);
        assertEquals(2, r.migrated);
        assertEquals(0, r.dropped);
        assertTrue(kv.data.get("openai").startsWith(SecretCodec.PREFIX_GCM));
        assertTrue(kv.data.get("openai_host").startsWith(SecretCodec.PREFIX_GCM));
        // 明文等价：升级前后读出来的必须是同一份数据
        assertEquals(apiKey, SecretCodec.decode(kv.data.get("openai"), gcm));
        assertEquals(host, SecretCodec.decode(kv.data.get("openai_host"), gcm));
        // 明文配置项原样保留（把它们"升级"成密文就等于把配置读没了）
        assertEquals("cfg-1", kv.data.get("active_api_id"));
        assertEquals("/storage/emulated/0/api_archive", kv.data.get("api_archive_path"));
        // 实际收益：密文与钥匙不再同处一个文件，且系统里已无人能解旧格式
        assertFalse(kv.data.containsKey("encryption_key"));
        assertFalse(kv.data.containsKey("iv_key"));
        assertEquals(2, kv.deletes);
        assertTrue(kv.isMigrated(SecretMigrator.DEFAULT_FLAG_KEY));
    }

    @Test
    public void longConfigListJson_survivesAndroidBase64LineWrapping() {
        // 旧值由 android.util.Base64.DEFAULT 写出：76 字符折行 + 尾随换行。
        // JDK 的基本解码器遇换行直接抛异常 —— 这是最容易把一次性搬运做坏的地方。
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 40; i++) {
            if (i > 0) json.append(',');
            json.append("{\"id\":\"cfg-").append(i)
                    .append("\",\"apiKey\":\"sk-abcdefghij").append(i)
                    .append("\",\"apiHost\":\"https://api.example").append(i)
                    .append(".com/v1\"}");
        }
        json.append(']');
        String expected = json.toString();
        MapKv kv = kvWith(
                "encryption_key", b64(LEGACY_KEY),
                "iv_key", b64(LEGACY_IV),
                "api_config_list", legacyCbc(expected));

        GcmStub gcm = new GcmStub();
        SecretMigrator.Result r = run(kv, gcm, LEGACY);

        assertTrue("长 JSON 应搬成 enc: " + r, r.complete);
        assertEquals(1, r.migrated);
        assertEquals(expected, SecretCodec.decode(kv.data.get("api_config_list"), gcm));
    }

    @Test
    public void unencDegradedAndRawPlaintextValues_areBothUpgraded() {
        String apiKey = "sk-unenc-test-key";
        MapKv kv = kvWith(
                "metaso_search", SecretMigrator.LEGACY_PLAIN_PREFIX
                        + SecretCodec.encodeBase64(apiKey.getBytes(StandardCharsets.UTF_8)),
                // 更早版本留下的无标记明文：形状不像密文 → 按明文搬走
                "bing_search", "plain-key-with-dashes-and-mixed-CASE-9");

        GcmStub gcm = new GcmStub();
        SecretMigrator.Result r = run(kv, gcm, LEGACY);

        assertTrue(r.toString(), r.complete);
        assertEquals(2, r.migrated);
        assertEquals(apiKey, SecretCodec.decode(kv.data.get("metaso_search"), gcm));
        assertEquals("plain-key-with-dashes-and-mixed-CASE-9",
                SecretCodec.decode(kv.data.get("bing_search"), gcm));
    }

    // ---------- 旧格式不做兼容：读不出就作废，绝不冒充明文 ----------

    @Test
    public void unreadableLegacyValue_isDroppedNotImpersonated() {
        // 旧主密钥已丢失（换机/恢复备份）：这条 CBC 救不回来。
        // 作废删除 = 界面显示"未配置"，用户重填即可；留着它则可能被某条宽容路径当成明文发出去。
        String cipher = legacyCbc("sk-orphaned-ciphertext");
        MapKv kv = kvWith("encryption_key", b64(LEGACY_KEY), "iv_key", b64(LEGACY_IV),
                "openai", cipher);

        SecretMigrator.LegacyCbcKey lostKeyMaterial = new SecretMigrator.LegacyCbcKey(
                b64(new byte[32]), b64(new byte[16]));
        GcmStub gcm = new GcmStub();
        SecretMigrator.Result r = run(kv, gcm, lostKeyMaterial);

        assertTrue("作废旧值后即可达成终态: " + r, r.complete);
        assertEquals(1, r.dropped);
        assertFalse("垃圾条目已删除", kv.data.containsKey("openai"));
        assertFalse(kv.data.containsKey("encryption_key"));

        // 稳态读取端：这种值一律 null，绝不返回那段 Base64
        assertNull(SecretCodec.decode(cipher, gcm));
        assertNull(SecretCodec.decode(SecretMigrator.LEGACY_PLAIN_PREFIX + "AAAA", gcm));
        assertNull(SecretCodec.decode("some-legacy-plaintext-value", gcm));
        assertNull(SecretCodec.decode("", gcm));
        assertNull(SecretCodec.decode(null, gcm));
    }

    @Test
    public void emptyLegacyValue_isDropped() {
        MapKv kv = kvWith("encryption_key", b64(LEGACY_KEY), "iv_key", b64(LEGACY_IV),
                "google_maps", legacyCbc(""));
        SecretMigrator.Result r = run(kv, new GcmStub(), LEGACY);
        assertTrue(r.toString(), r.complete);
        assertEquals(1, r.dropped);
        assertFalse(kv.data.containsKey("google_maps"));
        assertFalse(kv.data.containsKey("encryption_key"));
    }

    @Test
    public void decodeOnlyAcceptsKeystoreGcmFormat() {
        GcmStub gcm = new GcmStub();
        String enc = gcm.tryEncrypt("sk-real");
        assertEquals("sk-real", SecretCodec.decode(enc, gcm));

        // 主密钥换了（换机/恢复备份）：解不开就是没有，不返回哨兵密文
        assertNull(SecretCodec.decode(enc, new GcmStub()));

        // 被篡改：GCM 认证标签校验失败 → null
        String tampered = enc.substring(0, enc.length() - 4) + "AAAA";
        assertNull(SecretCodec.decode(tampered, gcm));

        // Keystore 不可用：即使是正确密文也返回 null（调用方按"未配置"处理）
        GcmStub down = new GcmStub();
        down.available = false;
        assertNull(SecretCodec.decode(enc, down));
    }

    // ---------- 写不成就什么都不动 ----------

    @Test
    public void encodeReturnsNullWhenKeystoreUnavailable_neverDowngradesToPlaintext() {
        GcmStub gcm = new GcmStub();
        assertTrue("正常路径产出 enc:",
                SecretCodec.encode("sk-x", gcm).startsWith(SecretCodec.PREFIX_GCM));
        gcm.available = false;
        assertNull(SecretCodec.encode("sk-x", gcm));
        gcm.available = true;
        gcm.silentlyReturnPlaintext = true;
        assertNull("返回原文不算加密成功", SecretCodec.encode("sk-x", gcm));
    }

    @Test
    public void keystoreUnavailable_touchesNothingAndKeepsLegacyKey() {
        String stored = legacyCbc("sk-keep-me");
        MapKv kv = kvWith("encryption_key", b64(LEGACY_KEY), "iv_key", b64(LEGACY_IV),
                "openai", stored);

        GcmStub gcm = new GcmStub();
        gcm.available = false;
        SecretMigrator.Result r = run(kv, gcm, LEGACY);

        assertFalse("密钥不可用时绝不能声称搬完", r.complete);
        assertEquals(stored, kv.data.get("openai"));
        assertTrue(kv.data.containsKey("encryption_key"));
        assertTrue(kv.data.containsKey("iv_key"));
        assertFalse(kv.isMigrated(SecretMigrator.DEFAULT_FLAG_KEY));
        assertEquals(0, kv.writes);
        assertEquals("连作废都不做", 0, kv.deletes);
    }

    @Test
    public void cipherSilentlyReturnsPlaintext_abortsBeforeAnyMutation() {
        // 探测一次 encode：若实现"失败返回原文"，写下去的将是"标成密文的明文"，
        // 还顺手删掉旧主密钥 —— 比不搬更糟，所以在动磁盘之前就放弃。
        String stored = legacyCbc("sk-sensitive");
        MapKv kv = kvWith("encryption_key", b64(LEGACY_KEY), "iv_key", b64(LEGACY_IV),
                "openai", stored);

        GcmStub gcm = new GcmStub();
        gcm.silentlyReturnPlaintext = true;
        SecretMigrator.Result r = run(kv, gcm, LEGACY);

        assertFalse(r.complete);
        assertEquals(stored, kv.data.get("openai"));
        assertEquals(0, kv.writes);
        assertEquals(0, kv.deletes);
        assertTrue(kv.data.containsKey("encryption_key"));
    }

    // ---------- 并发：用户刚保存的不许被覆盖 ----------

    @Test
    public void concurrentWriteByUser_isNeverOverwritten() {
        MapKv kv = kvWith(
                "encryption_key", b64(LEGACY_KEY),
                "iv_key", b64(LEGACY_IV),
                "hefeng_weather", legacyCbc("qweather-old-key"),
                "openai", legacyCbc("sk-old"));

        final GcmStub gcm = new GcmStub();
        final String userJustSaved = gcm.tryEncrypt("sk-user-just-saved");
        // 迁移读到 openai 的旧快照后、落笔前，用户保存了新密钥
        kv.beforeReplace = new MapKv.ReplaceHook() {
            private boolean done;

            @Override
            public void before(String key) {
                if (done || !"openai".equals(key)) return;
                done = true;
                kv.data.put("openai", userJustSaved);
            }
        };

        SecretMigrator.Result r = run(kv, gcm, LEGACY);

        assertEquals("用户刚写入的值必须活着", userJustSaved, kv.data.get("openai"));
        assertEquals("sk-user-just-saved", SecretCodec.decode(kv.data.get("openai"), gcm));
        assertEquals("同批其他项照常搬", 1, r.migrated);
        assertEquals(1, r.conflicts);
        assertFalse("让位后本次不算完成，留待下次启动", r.complete);
        assertTrue("旧主密钥必须保留", kv.data.containsKey("encryption_key"));
    }

    // ---------- 幂等 / 崩溃恢复 ----------

    @Test
    public void migrationIsIdempotent_andSurvivesCrashBetweenFlagAndCleanup() {
        MapKv kv = kvWith("encryption_key", b64(LEGACY_KEY), "iv_key", b64(LEGACY_IV),
                "openai", legacyCbc("sk-round-one"));
        GcmStub gcm = new GcmStub();

        SecretMigrator.Result first = run(kv, gcm, LEGACY);
        assertTrue(first.toString(), first.complete);
        String afterFirst = kv.data.get("openai");

        SecretMigrator.Result second = run(kv, gcm, SecretMigrator.LegacyCbcKey.none());
        assertTrue(second.toString(), second.complete);
        assertEquals("已置标志就整批跳过", 0, second.scanned);
        assertEquals("二次运行不得改写", afterFirst, kv.data.get("openai"));

        // 模拟"置了标志但在删旧密钥前崩溃"：再跑一次必须把清理补完
        kv.data.put("encryption_key", b64(LEGACY_KEY));
        kv.data.put("iv_key", b64(LEGACY_IV));
        SecretMigrator.Result third = run(kv, gcm, SecretMigrator.LegacyCbcKey.none());
        assertTrue(third.complete);
        assertFalse(kv.data.containsKey("encryption_key"));
        assertFalse(kv.data.containsKey("iv_key"));
        assertEquals("sk-round-one", SecretCodec.decode(kv.data.get("openai"), gcm));
    }

    @Test
    public void alreadyMigrated_skipsScanningEntirely() {
        MapKv kv = kvWith(SecretMigrator.DEFAULT_FLAG_KEY, "true",
                "openai", new GcmStub().tryEncrypt("sk-x"),
                "encryption_key", b64(LEGACY_KEY));
        SecretMigrator.Result r = run(kv, new GcmStub(), LEGACY);
        assertTrue(r.complete);
        assertEquals("已完成就不该再逐条加解密", 0, r.scanned);
        assertFalse("补删遗留的旧主密钥", kv.data.containsKey("encryption_key"));
    }

    @Test
    public void emptyStore_reachesTerminalStateWithoutWriting() {
        MapKv kv = kvWith("active_api_id", "cfg-1");
        SecretMigrator.Result r = run(kv, new GcmStub(), SecretMigrator.LegacyCbcKey.none());
        assertTrue(r.toString(), r.complete);
        assertEquals(0, r.scanned);
        assertTrue(kv.isMigrated(SecretMigrator.DEFAULT_FLAG_KEY));
        assertEquals("cfg-1", kv.data.get("active_api_id"));
    }

    // ---------- 旧格式识别的内部判据 ----------

    @Test
    public void legacyReader_classifiesEachHistoricalShape() {
        assertEquals("sk-cbc", SecretMigrator.readLegacy(legacyCbc("sk-cbc"), LEGACY));
        assertEquals("sk-unenc", SecretMigrator.readLegacy(
                SecretMigrator.LEGACY_PLAIN_PREFIX + SecretCodec.encodeBase64(
                        "sk-unenc".getBytes(StandardCharsets.UTF_8)), LEGACY));
        assertEquals("readable-text", SecretMigrator.readLegacy("readable-text", LEGACY));
        assertNull("形状像密文又解不开 → 作废",
                SecretMigrator.readLegacy(legacyCbc("x"), SecretMigrator.LegacyCbcKey.none()));
        assertNull(SecretMigrator.readLegacy(null, LEGACY));
        assertTrue(SecretMigrator.looksLikeCbcBlob(legacyCbc("1234567890abcdef")));
        assertFalse(SecretMigrator.looksLikeCbcBlob("not-a-cipher"));
    }

    @Test
    public void tolerantBase64HandlesAndroidLineWrapping() {
        // JDK 基本解码器会因换行抛异常；MIME 解码器必须吃下旧数据的折行与尾随换行
        String wrapped = SecretCodec.encodeBase64("0123456789".getBytes(StandardCharsets.UTF_8))
                + "\n";
        assertEquals("0123456789",
                SecretCodec.utf8(SecretCodec.decodeBase64Tolerant(wrapped)));
        assertNull(SecretCodec.decodeBase64Tolerant(null));
    }
}
