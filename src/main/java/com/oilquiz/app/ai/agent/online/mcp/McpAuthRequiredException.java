package com.oilquiz.app.ai.agent.online.mcp;

/**
 * 服务端用 401 + {@code WWW-Authenticate: Bearer resource_metadata="..."} 表达"要先授权"。
 *
 * <p>把它做成独立异常而不是一句 "HTTP 401" 的原因：401 有两种完全不同的下一步——
 * 密钥填错（去管理页改引用）与需要走一次浏览器授权（去 OAuth）。
 * 只有携带挑战信息，上层才分得清该给哪个按钮；混成"鉴权失败"就会让用户在密钥框里
 * 反复试一个本来就没密钥可填的服务。</p>
 */
public class McpAuthRequiredException extends Exception {

    private final McpOAuthEndpoints.Challenge challenge;

    public McpAuthRequiredException(McpOAuthEndpoints.Challenge challenge, String message) {
        super(message);
        this.challenge = challenge;
    }

    /** 401 里解析出的挑战（可能没有 resource_metadata，只有 scope）。 */
    public McpOAuthEndpoints.Challenge getChallenge() {
        return challenge;
    }

    /** 挑战里给了受保护资源文档地址 → 能走标准发现链。 */
    public boolean canDiscoverAuthorizationServer() {
        return challenge != null;
    }

    /** 服务商明确给了授权文档地址（能自动发现授权服务）。 */
    public boolean hasResourceMetadata() {
        return challenge != null && challenge.hasResourceMetadata();
    }

    /**
     * 由一次失败的 HTTP 响应构造异常。
     *
     * <p>只处理 401/403：其余状态码（404 会话失效、400 报文不对、5xx 服务侧故障）
     * 的下一步完全不同，不该混进"要授权"这一类。</p>
     *
     * @return 不是 401/403 时返回 {@code null}，调用方保留原有的通用错误
     */
    public static McpAuthRequiredException fromFailure(String endpoint, int code,
                                                       String wwwAuthenticate) {
        if (code != 401 && code != 403) return null;
        String masked = McpServerConfig.maskUrl(endpoint);
        if (code == 403) {
            return new McpAuthRequiredException(null,
                    "无权访问（HTTP 403）：" + masked + " —— 密钥可能不属于该账号，或该服务没开对应权限");
        }
        McpOAuthEndpoints.Challenge challenge = McpOAuthEndpoints.parseChallenge(code, wwwAuthenticate);
        if (challenge.hasResourceMetadata()) {
            return new McpAuthRequiredException(challenge,
                    "需要浏览器授权（HTTP 401）：" + masked + " —— 服务商给了授权文档地址，点「🔐 授权」走一次");
        }
        return new McpAuthRequiredException(challenge,
                "鉴权被拒（HTTP 401）：" + masked + " —— 先确认密钥引用；若该服务要求浏览器授权，用「🔐 授权」");
    }
}
