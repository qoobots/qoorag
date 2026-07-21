package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.SessionInfo;

import java.util.Optional;

/**
 * 企业身份对接抽象（#13）：对接 SSO / OAuth2 / LDAP / IM 的统一入口。
 * <p>当前 MVP 仅使用本地账号（系统管理员 + 知识管理员）跑通业务，本接口为后续扩展预留骨架：
 * <ul>
 *   <li>未来实现 {@code OAuth2IdentityProvider}（微信 / 飞书 / 钉钉 / 企业微信）</li>
 *   <li>未来实现 {@code LdapIdentityProvider}（AD / OpenLDAP）</li>
 *   <li>未来实现 {@code SamlIdentityProvider}</li>
 * </ul>
 * 本地账号认证仍由 {@link AuthService} 负责，本接口暂不接入登录主链路（fail-close，由本地认证兜底）。
 */
public interface IdentityProvider {

    /** 外部身份凭证类型 */
    enum Type { LOCAL, OAUTH2, LDAP, SAML, IM }

    /**
     * 用外部身份换取系统会话。
     *
     * @return 无法识别时返回 empty（由本地认证兜底）
     */
    Optional<SessionInfo> authenticate(Type type, String principal, String credential);
}
