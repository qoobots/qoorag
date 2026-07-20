package com.qoobot.qoorag.common;

import java.util.Set;

/** 当前请求的安全上下文信息（会话令牌或 API Key 解析得到） */
public class SessionInfo {
    public String token;            // 会话令牌（仅会话登录有）
    public Long userId;             // 用户 ID（API Key 调用为 null）
    public Long tenantId;           // 租户 ID（4.9 隔离键）
    public Long kbId;               // 知识库 ID（仅 API Key 调用有）
    public Set<String> roles;       // 角色名集合（仅会话登录有）
    public boolean isApiKey;        // true=API Key 调用，false=会话调用

    public boolean hasRole(String roleName) {
        return roles != null && roles.contains(roleName);
    }

    public String getToken() { return token; }
    public Long getUserId() { return userId; }
    public Long getTenantId() { return tenantId; }
    public Long getKbId() { return kbId; }
    public Set<String> getRoles() { return roles; }
    public boolean isApiKey() { return isApiKey; }
}
