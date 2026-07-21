package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.SessionInfo;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 本地身份兜底实现（#13）：MVP 阶段所有账号均为本地账号，由 {@link AuthService} 处理登录；
 * 此处仅作骨架占位，返回 empty 交由本地认证兜底。
 */
@Component
public class LocalIdentityProvider implements IdentityProvider {

    @Override
    public Optional<SessionInfo> authenticate(Type type, String principal, String credential) {
        // MVP 不通过此接口登录；本地认证由 AuthService.login 完成。
        return Optional.empty();
    }
}
