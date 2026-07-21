package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.SessionInfo;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** IdentityProvider 骨架单测（#13）：MVP 本地兜底返回 empty */
class IdentityProviderTest {

    @Test
    void localProvider_returns_empty() {
        IdentityProvider provider = new LocalIdentityProvider();
        Optional<SessionInfo> r = provider.authenticate(IdentityProvider.Type.OAUTH2, "u", "c");
        assertTrue(r.isEmpty());
    }
}
