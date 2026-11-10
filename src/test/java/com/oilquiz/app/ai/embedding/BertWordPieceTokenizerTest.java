package com.oilquiz.app.ai.embedding;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * BERT WordPiece 分词的确定性断言：用自造的小词表钉住行为，
 * 不依赖 21128 行的真实词表（那个只在设备上加载）。
 */
public class BertWordPieceTokenizerTest {

    /** id 由行号决定：0 [PAD] 1 [UNK] 2 [CLS] 3 [SEP] 4 光 5 气 6 泄 7 漏 8 的 9 abc 10 ##b */
    private static final String VOCAB =
            "[PAD]\n[UNK]\n[CLS]\n[SEP]\n光\n气\n泄\n漏\n的\nabc\n##b\n";

    private BertWordPieceTokenizer tokenizer;

    @Before
    public void setUp() throws Exception {
        tokenizer = BertWordPieceTokenizer.fromStream(
                new ByteArrayInputStream(VOCAB.getBytes(StandardCharsets.UTF_8)), 320);
    }

    @Test
    public void chineseIsSplitPerCharacter() {
        assertArrayEquals(new int[]{2, 4, 5, 6, 7, 3}, tokenizer.encode("光气泄漏").ids);
        assertEquals(6, tokenizer.encode("光气泄漏").mask.length);
    }

    @Test
    public void masksAreAllOnes() {
        BertWordPieceTokenizer.Encoded e = tokenizer.encode("光气泄漏");
        for (int m : e.mask) assertEquals(1, m);
        for (int t : e.types) assertEquals(0, t);
    }

    @Test
    public void englishIsLowercasedAndWordPieced() {
        assertArrayEquals(new int[]{2, 9, 3}, tokenizer.encode("ABC").ids);
        assertArrayEquals(new int[]{2, 9, 10, 3}, tokenizer.encode("abcb").ids);
    }

    @Test
    public void unknownWordBecomesUnk() {
        assertArrayEquals(new int[]{2, 1, 3}, tokenizer.encode("xyz").ids);
    }

    @Test
    public void punctuationGetsItsOwnToken() {
        // 逗号不在词表里 → [UNK]；关键是它没有和中文粘在一起
        assertArrayEquals(new int[]{2, 4, 1, 5, 3}, tokenizer.encode("光,气").ids);
    }

    @Test
    public void overlongInputIsTruncatedButKeepsSep() throws Exception {
        BertWordPieceTokenizer shortTok = BertWordPieceTokenizer.fromStream(
                new ByteArrayInputStream(VOCAB.getBytes(StandardCharsets.UTF_8)), 4);
        int[] ids = shortTok.encode("光气泄漏").ids;
        assertEquals(4, ids.length);
        assertEquals(2, ids[0]);
        assertEquals(3, ids[3]);
    }

    @Test
    public void emptyTextIsJustClsSep() {
        assertArrayEquals(new int[]{2, 3}, tokenizer.encode("").ids);
        assertArrayEquals(new int[]{2, 3}, tokenizer.encode(null).ids);
    }
}
