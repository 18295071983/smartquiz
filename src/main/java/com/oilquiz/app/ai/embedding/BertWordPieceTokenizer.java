package com.oilquiz.app.ai.embedding;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 纯 Java 版 BERT WordPiece 分词器（不引任何 NLP 库、不带 JNI）。
 *
 * <p>bge-base-zh-v1.5 用的是 bert-base-chinese 的 21128 词表：中文逐字切、英文小写后走 wordpiece、
 * 中文标点单独成词。本地向量只用到这几步，不需要 sentencepiece。</p>
 *
 * <p>这是"本地 embedding 模型"的前置件——ONNX 只吃 token id，分词得自己做。</p>
 *
 * <p>特殊 token 的 id <b>从词表里读</b>，不写死：真实 BERT 词表里 [UNK]=100/[CLS]=101/[SEP]=102，
 * 但小词表（测试用的那种）不是这类值。写死会在小词表上静默编错向量，而向量错是看不出来的。</p>
 */
public final class BertWordPieceTokenizer {

    /** 真实 bert-base-chinese 词表里的默认 id，仅在词表缺 [UNK] 时兜底 */
    public static final int ID_PAD = 0;
    public static final int ID_UNK = 100;
    public static final int ID_CLS = 101;
    public static final int ID_SEP = 102;

    private static final String TOKEN_CLS = "[CLS]";
    private static final String TOKEN_SEP = "[SEP]";
    private static final String TOKEN_UNK = "[UNK]";
    private static final String CONTINUATION = "##";

    /** 单个英文词最多处理的字符数（对齐 BERT 原实现的 100 字符上限） */
    private static final int MAX_CHARS_PER_WORD = 100;

    private final Map<String, Integer> vocab;
    private final int maxLen;
    private final int idCls;
    private final int idSep;
    private final int idUnk;

    /** 一次编码结果：ids / attention mask / token type ids，三者长度恒等且都含 [CLS]/[SEP] */
    public static final class Encoded {
        public final int[] ids;
        public final int[] mask;
        public final int[] types;

        Encoded(int[] ids, int[] mask, int[] types) {
            this.ids = ids;
            this.mask = mask;
            this.types = types;
        }

        public int length() {
            return ids.length;
        }
    }

    private BertWordPieceTokenizer(Map<String, Integer> vocab, int maxLen) {
        this.vocab = vocab;
        this.maxLen = maxLen <= 0 ? 512 : maxLen;
        this.idCls = vocab.getOrDefault(TOKEN_CLS, ID_CLS);
        this.idSep = vocab.getOrDefault(TOKEN_SEP, ID_SEP);
        this.idUnk = vocab.getOrDefault(TOKEN_UNK, ID_UNK);
    }

    /** 词表文件一行一个 token，行号即 id（BERT 的 vocab.txt 格式） */
    public static BertWordPieceTokenizer fromVocabFile(File file, int maxLen) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return fromStream(in, maxLen);
        }
    }

    /**
     * 从流读取（assets / 测试内联词表用）。
     *
     * @throws IOException 词表为空或缺 [CLS]/[SEP]——那种词表编出来的向量一定错，早失败
     */
    public static BertWordPieceTokenizer fromStream(InputStream in, int maxLen) throws IOException {
        Map<String, Integer> map = new HashMap<>(1 << 15);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int id = 0;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                    line = line.substring(0, line.length() - 1);
                }
                if (!map.containsKey(line)) {
                    map.put(line, id);
                }
                id++;
            }
        }
        if (map.isEmpty() || !map.containsKey(TOKEN_CLS) || !map.containsKey(TOKEN_SEP)) {
            throw new IOException("词表不合法（缺 [CLS]/[SEP]，或文件为空）");
        }
        return new BertWordPieceTokenizer(map, maxLen);
    }

    public int vocabSize() {
        return vocab.size();
    }

    public int maxLen() {
        return maxLen;
    }

    /**
     * 编码一段文本：[CLS] tokens... [SEP]，超长截断（保 CLS 与 SEP）。
     * mask 全 1、type 全 0（单句输入，不需要 segment 区分）。
     */
    public Encoded encode(String text) {
        List<Integer> body = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            for (String token : tokenize(text)) {
                Integer id = vocab.get(token);
                body.add(id != null ? id : idUnk);
            }
        }

        int budget = maxLen - 2;
        if (budget < 0) budget = 0;
        if (body.size() > budget) {
            body = new ArrayList<>(body.subList(0, budget));
        }

        int n = body.size() + 2;
        int[] ids = new int[n];
        int[] mask = new int[n];
        int[] types = new int[n];
        ids[0] = idCls;
        ids[n - 1] = idSep;
        for (int i = 0; i < body.size(); i++) {
            ids[i + 1] = body.get(i);
        }
        Arrays.fill(mask, 1);
        return new Encoded(ids, mask, types);
    }

    private List<String> tokenize(String text) {
        List<String> basic = basicTokenize(text);
        List<String> out = new ArrayList<>(basic.size() + 8);
        for (String token : basic) {
            wordPiece(token, out);
        }
        return out;
    }

    /** Basic：中文/日文/谚文逐字，标点独立，其余（英文/数字）连续成词后小写去重音 */
    private static List<String> basicTokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int width = Character.charCount(cp);
            char c = (char) cp;

            if (cp == 0 || cp == 0xFFFD) {
                // 控制符/替换符：直接丢
            } else if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                flush(word, tokens);
            } else if (isCjk(cp)) {
                flush(word, tokens);
                tokens.add(new String(Character.toChars(cp)));
            } else if (isPunctuation(c)) {
                flush(word, tokens);
                tokens.add(String.valueOf(c));
            } else {
                word.appendCodePoint(cp);
            }
            i += width;
        }
        flush(word, tokens);
        return tokens;
    }

    private static void flush(StringBuilder word, List<String> tokens) {
        if (word.length() == 0) return;
        String w = normalizeWord(word.toString());
        if (!w.isEmpty()) tokens.add(w);
        word.setLength(0);
    }

    /** 小写 + 去拉丁重音（中文不受影响），并兜掉过长的词 */
    private static String normalizeWord(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.length() > MAX_CHARS_PER_WORD) w = w.substring(0, MAX_CHARS_PER_WORD);
        String norm = Normalizer.normalize(w, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(norm.length());
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /** 贪心最长匹配；首段不加 `##`，后续段加 `##`；整词匹配不上记 [UNK] */
    private void wordPiece(String token, List<String> out) {
        if (token.isEmpty()) return;
        if (vocab.containsKey(token)) {
            out.add(token);
            return;
        }
        int length = Math.min(token.length(), MAX_CHARS_PER_WORD);
        int start = 0;
        List<String> pieces = new ArrayList<>();
        while (start < length) {
            int end = length;
            String hit = null;
            while (start < end) {
                String sub = token.substring(start, end);
                String candidate = start > 0 ? CONTINUATION + sub : sub;
                if (vocab.containsKey(candidate)) {
                    hit = candidate;
                    break;
                }
                end--;
            }
            if (hit == null) {
                out.add(TOKEN_UNK);
                return;
            }
            pieces.add(hit);
            start = end;
        }
        out.addAll(pieces);
    }

    /** CJK 统一表意 + 假名 + 谚文：按字切 */
    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0xAC00 && cp <= 0xD7A3);
    }

    /** BERT 的 `_is_punctuation`：ASCII 符号区 + Unicode 标点类 */
    private static boolean isPunctuation(char c) {
        if ((c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96)
                || (c >= 123 && c <= 126)) {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }
}
