package com.oilquiz.app.ai.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 一次性把旧密钥存储升级到 Keystore AES-256-GCM —— 旧格式<b>不做长期兼容</b>。
 *
 * <p><b>背景</b>：旧 {@code APIKeyManager} 用 AES-256-CBC + 固定 IV，并把主密钥与 IV 明文存在
 * 同一个 SharedPreferences 文件里（拿到文件就同时拿到钥匙和密文）。新格式
 * （{@link ApiKeyCipher}）把主密钥关进 Android Keystore、每次随机 IV。本类负责搬运，
 * 搬完之后旧主密钥从磁盘删除，读取路径（{@link SecretCodec}）只认 {@code enc:} 一种格式。</p>
 *
 * <p><b>策略（按要求收敛为"旧的不需要兼容"）</b>：</p>
 * <ul>
 *   <li>能解出明文的旧值 → 升级成 {@code enc:}，用户无需重填密钥。</li>
 *   <li>解不出的旧值（旧主密钥已丢失、值被改坏、空值）→ <b>直接作废删除</b>，
 *       不当明文返回、也不留兼容读取路径；用户重填一次即恢复。</li>
 *   <li>Keystore 当前不可用 → <b>整笔放弃、磁盘一字不改</b>，下次启动再试。
 *       这条是底线：新格式写不出来时，把旧值删掉等于凭空毁掉用户数据。</li>
 *   <li>迁移期间用户并发保存 → 比较再写（CAS）失败就让位，本次不算完成，下轮重跑
 *       （那时该值已是 {@code enc:}，直接跳过）。</li>
 * </ul>
 *
 * <p>与 Android API 完全解耦（存储与密码学均按接口注入），因此迁移逻辑连同"密钥不可用"
 * "主密钥丢失"这些分支都能在 JVM 单测里跑真密码学，不需要 Robolectric。</p>
 */
public final class SecretMigrator {

    /** 完成标志键：置位后每次启动只做一次读，不再扫描改写数据。 */
    public static final String DEFAULT_FLAG_KEY = "secrets_migrated_v2";

    /** 旧 AES-CBC 固定 IV 格式的前缀（历史降级档，只在本类的一次性搬运里认识它）。 */
    static final String LEGACY_PLAIN_PREFIX = "UNENC:";

    private static final String CBC_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int CBC_BLOCK_BYTES = 16;

    /**
     * SharedPreferences 的最小抽象。实现必须返回<b>已提交</b>的写入结果，
     * 以便并发写发生时放弃本条。
     */
    public interface SecretKv {
        /** 当前全部键。 */
        Set<String> keys();

        /** 读原值（未解码），不存在或非字符串条目返回 {@code null}。 */
        String get(String key);

        /**
         * 仅当当前值仍等于 {@code expectedCurrent} 时写入 {@code newValue}。
         *
         * @return 是否写入成功（值已变化则 false）
         */
        boolean replaceIfUnchanged(String key, String expectedCurrent, String newValue);

        /** 删除条目（作废解不出的旧值、清理旧主密钥）。 */
        void delete(String key);

        /** 写完成标志（幂等）。 */
        void markMigrated(String flagKey);

        /** 读完成标志。 */
        boolean isMigrated(String flagKey);
    }

    /**
     * 旧格式主密钥材料（AES 主密钥 + 固定 IV）。
     *
     * <p>只服务本次搬运：升级完成后由 {@link #run} 从磁盘删除，此后系统里不存在任何能解它的代码路径。</p>
     */
    public static final class LegacyCbcKey {

        private final byte[] keyBytes;
        private final byte[] ivBytes;

        /**
         * @param keyBase64 SharedPreferences 里的旧主密钥（Android Base64.DEFAULT，可能带换行）
         * @param ivBase64  SharedPreferences 里的旧固定 IV
         */
        public LegacyCbcKey(String keyBase64, String ivBase64) {
            byte[] key = SecretCodec.decodeBase64Tolerant(keyBase64);
            byte[] iv = SecretCodec.decodeBase64Tolerant(ivBase64);
            // 长度合法性一次判掉，之后 usable() 是 O(1)；空参数 = 新装应用没有旧密钥
            this.keyBytes = (key != null && (key.length == 16 || key.length == 24 || key.length == 32))
                    ? key : null;
            this.ivBytes = (iv != null && iv.length == CBC_BLOCK_BYTES) ? iv : null;
        }

        /** 没有旧主密钥（新装应用，或上一轮迁移已删干净）。 */
        public static LegacyCbcKey none() {
            return new LegacyCbcKey(null, null);
        }

        public boolean usable() {
            return keyBytes != null && ivBytes != null;
        }

        /** @return 明文；解不开返回 {@code null} */
        String decrypt(String storedNoPrefix) {
            if (!usable() || storedNoPrefix == null) return null;
            byte[] cipherBytes = SecretCodec.decodeBase64Tolerant(storedNoPrefix);
            if (cipherBytes == null || cipherBytes.length == 0
                    || cipherBytes.length % CBC_BLOCK_BYTES != 0) {
                return null;
            }
            try {
                Cipher cipher = Cipher.getInstance(CBC_TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                        new IvParameterSpec(ivBytes));
                return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** 一次运行的结果，用于日志与测试断言。 */
    public static final class Result {
        /** 扫描到的密钥条目数（不含密钥材料与完成标志）。 */
        public int scanned;
        /** 本次升级成功的条数。 */
        public int migrated;
        /** 本来就是 {@code enc:}，跳过的条数。 */
        public int alreadyTarget;
        /** 解不出（或空值）被作废删除的条数。 */
        public int dropped;
        /** 与用户并发写冲突、本次让位的条数。 */
        public int conflicts;
        /** 是否达成终态（标志已置、旧主密钥已删）。 */
        public boolean complete;

        @Override
        public String toString() {
            return "scanned=" + scanned + " migrated=" + migrated + " already=" + alreadyTarget
                    + " dropped=" + dropped + " conflicts=" + conflicts + " complete=" + complete;
        }
    }

    private SecretMigrator() {
    }

    /**
     * 执行一次搬运。先规划、后落笔：任何"写不出来"的前置条件都在动磁盘之前判掉。
     *
     * @param kv                 存储（生产为 SharedPreferences 适配层）
     * @param gcm                Keystore GCM 主密钥
     * @param legacy             旧 CBC 主密钥材料（可为 {@link LegacyCbcKey#none()}）
     * @param skipKeys           必须原样保留的键（明文配置项、完成标志、旧密钥材料本身）
     * @param legacyMaterialKeys 旧主密钥与 IV 的键名；达成终态时删除
     * @param flagKey            完成标志键名
     */
    public static Result run(SecretKv kv,
                             SecretCodec.GcmCipher gcm,
                             LegacyCbcKey legacy,
                             Set<String> skipKeys,
                             List<String> legacyMaterialKeys,
                             String flagKey) {
        Result r = new Result();
        if (kv == null) return r;

        // 已迁移：只需补做"标志已置但旧密钥没删干净"的收尾（上次崩在两步之间）
        if (kv.isMigrated(flagKey)) {
            deleteAll(kv, legacyMaterialKeys);
            r.complete = true;
            return r;
        }

        Set<String> keys = kv.keys();
        if (keys == null) keys = Collections.emptySet();

        // 排序后处理：同样的数据得到同样的统计与日志，回归测试可复现
        List<String> ordered = new ArrayList<>(keys);
        Collections.sort(ordered);

        List<String> toDrop = new ArrayList<>();
        List<String[]> toUpgrade = new ArrayList<>(); // {key, 起始快照, 明文}
        for (String key : ordered) {
            if (isBookkeeping(key, skipKeys, flagKey, legacyMaterialKeys)) continue;
            String stored = kv.get(key);
            if (stored == null || stored.isEmpty()) continue;
            r.scanned++;

            if (SecretCodec.isTargetFormat(stored)) {
                r.alreadyTarget++;
                continue;
            }
            String plain = readLegacy(stored, legacy);
            if (plain == null || plain.isEmpty()) {
                // 解不出 / 空值：旧格式不再被任何读取路径认识，留着只是垃圾 → 作废
                toDrop.add(key);
                continue;
            }
            toUpgrade.add(new String[]{key, stored, plain});
        }

        boolean hasWork = !toUpgrade.isEmpty() || !toDrop.isEmpty();
        if (hasWork && !canWriteTargetFormat(gcm)) {
            // 写不出 enc: 就一个字都不动（连"作废垃圾"也不做）：
            // 此刻删除旧值可能在错误的判断上毁掉数据，写明文则等于没加密。留待下次启动。
            r.complete = false;
            return r;
        }
        for (String key : toDrop) {
            kv.delete(key);
            r.dropped++;
        }
        for (String[] item : toUpgrade) {
            String key = item[0];
            String stored = item[1];
            String newValue = SecretCodec.encode(item[2], gcm);
            if (newValue == null) {
                // 规划后 Keystore 恰好失效：不写、不删，留待下次
                r.conflicts++;
                continue;
            }
            if (!kv.replaceIfUnchanged(key, stored, newValue)) {
                // 用户刚存了新密钥：让位，本次不算完成（下次它已是 enc:，直接跳过）
                r.conflicts++;
                continue;
            }
            if (!item[2].equals(SecretCodec.decode(kv.get(key), gcm))) {
                // 写坏了（理论上不该发生）：回滚我们写的这条，保留现场
                kv.replaceIfUnchanged(key, newValue, stored);
                r.conflicts++;
                continue;
            }
            r.migrated++;
        }

        r.complete = r.conflicts == 0;
        if (r.complete) {
            kv.markMigrated(flagKey);
            deleteAll(kv, legacyMaterialKeys);
        }
        return r;
    }

    /**
     * 目标格式此刻写得出来吗？
     *
     * <p>不只看 {@code isAvailable()}：还要真探一次 {@code encode}，因为
     * {@link ApiKeyCipher} 这类实现在 Keystore 异常时会"返回原文"以示不阻塞功能 ——
     * 那种值一旦被当作密文写下去，就等于把明文标成了已加密。探测成本是一次 GCM 运算。</p>
     */
    private static boolean canWriteTargetFormat(SecretCodec.GcmCipher gcm) {
        return gcm != null && gcm.isAvailable()
                && SecretCodec.encode("keystore-probe", gcm) != null;
    }

    /**
     * 读旧格式的值 —— 只在本类的一次性搬运里使用。
     *
     * <p>三种历史形态：{@code UNENC:} + Base64(明文)（旧的降级档）、无标记的 CBC 密文、
     * 更早的无标记明文。歧义按"形状是否像 CBC 密文"（可 Base64 解成 16 字节整数倍）判定：
     * 像密文又解不开 → {@code null}（作废），不像密文 → 当明文搬走。</p>
     */
    static String readLegacy(String stored, LegacyCbcKey legacy) {
        if (stored == null || stored.isEmpty()) return null;
        if (stored.startsWith(LEGACY_PLAIN_PREFIX)) {
            return SecretCodec.utf8(
                    SecretCodec.decodeBase64Tolerant(stored.substring(LEGACY_PLAIN_PREFIX.length())));
        }
        if (legacy != null && legacy.usable()) {
            String plain = legacy.decrypt(stored);
            if (plain != null) return plain;
        }
        if (looksLikeCbcBlob(stored)) {
            return null; // 疑似 CBC 但解不开：主密钥已丢，这条救不回来
        }
        return stored; // 无标记明文
    }

    /** 是否"形状像旧 CBC 密文"：可 Base64 解码且长度为块长整数倍。 */
    static boolean looksLikeCbcBlob(String stored) {
        if (stored == null || stored.isEmpty()) return false;
        byte[] raw = SecretCodec.decodeBase64Tolerant(stored);
        return raw != null && raw.length > 0 && raw.length % CBC_BLOCK_BYTES == 0;
    }

    private static boolean isBookkeeping(String key, Set<String> skipKeys, String flagKey,
                                         List<String> legacyMaterialKeys) {
        if (key == null) return true;
        if (key.equals(flagKey)) return true;
        if (skipKeys != null && skipKeys.contains(key)) return true;
        return legacyMaterialKeys != null && legacyMaterialKeys.contains(key);
    }

    private static void deleteAll(SecretKv kv, List<String> keys) {
        if (keys == null) return;
        for (String key : keys) {
            // 只在确实存在时删：已迁移的每次启动都会走这条路，避免无谓的磁盘提交
            if (key != null && kv.get(key) != null) kv.delete(key);
        }
    }
}
