package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * PKCE 与 state 的契约。
 *
 * <p>这块错了不会立刻表现出来——授权能走完，令牌却绑不住，等于把授权码流程退化成
 * 可被中间人换码的流程。所以用 RFC 7636 附录 B 的公开向量钉死 S256 算法本身，
 * 而不是只测"长度对不对"。</p>
 */
public class McpPkceTest {

    /** RFC 7636 附录 B.1 的官方向量。 */
    private static final String RFC_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String RFC_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    public void s256MatchesThePublishedRfcVector() {
        assertEquals(RFC_CHALLENGE, McpPkce.challengeOf(RFC_VERIFIER));
    }

    @Test
    public void verifierFitsRfc7636LengthAndCharset() {
        String v = McpPkce.newVerifier();
        assertTrue("长度须在 43-128：" + v.length(), v.length() >= 43 && v.length() <= 128);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            assertTrue("非法字符 " + c + " 会被服务商拒收", ok);
        }
        assertFalse("不能带 base64 填充（unreserved 集不含 =）", v.contains("="));
    }

    @Test
    public void verifierAndStateAreNotReused() {
        assertFalse(McpPkce.newVerifier().equals(McpPkce.newVerifier()));
        assertFalse(McpPkce.newState().equals(McpPkce.newState()));
        assertEquals("state 用 32 位十六进制", 32, McpPkce.newState().length());
    }

    @Test
    public void stateComparisonIsExactAndNullSafe() {
        String s = McpPkce.newState();
        assertTrue(McpPkce.stateMatches(s, s));
        assertFalse(McpPkce.stateMatches(s, s.substring(0, 31)));
        assertFalse(McpPkce.stateMatches(s, null));
        assertFalse(McpPkce.stateMatches(null, s));
    }

    @Test
    public void onlyS256CountsAsSafeForPublicClients() {
        assertTrue(McpPkce.supportsS256(java.util.Arrays.asList("plain", "S256")));
        assertFalse("只声明 plain 等于没做 PKCE",
                McpPkce.supportsS256(java.util.Arrays.asList("plain")));
        assertFalse(McpPkce.supportsS256(null));
        assertFalse(McpPkce.supportsS256(new java.util.ArrayList<String>()));
    }
}
