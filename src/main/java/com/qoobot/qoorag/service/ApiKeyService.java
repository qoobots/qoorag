package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.entity.ApiKey;
import com.qoobot.qoorag.repository.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** API Key 管理（4.10，按知识库签发） */
@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final AuthService authService;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, AuthService authService) {
        this.apiKeyRepository = apiKeyRepository;
        this.authService = authService;
    }

    public List<ApiKey> list(Long kbId) {
        return apiKeyRepository.findByKbIdAndDeletedAtIsNull(kbId);
    }

    /** 创建并返回明文 Key（仅此一次可见） */
    @Transactional
    public ApiKeyMaterial create(Long kbId, String name) {
        Long tenantId = SecurityContext.get().getTenantId();
        AuthService.ApiKeyMaterial material = authService.generateApiKey();
        ApiKey key = new ApiKey();
        key.setKbId(kbId);
        key.setTenantId(tenantId);
        key.setName(name);
        key.setKeyHash(material.keyHash());
        key.setStatus("ACTIVE");
        key.setRateLimit(60);
        key.setCreatedAt(LocalDateTime.now());
        apiKeyRepository.save(key);
        return new ApiKeyMaterial(material.rawKey(), key.getId());
    }

    @Transactional
    public void revoke(Long kbId, Long keyId) {
        apiKeyRepository.findByKbIdAndDeletedAtIsNull(kbId).stream()
                .filter(k -> k.getId().equals(keyId))
                .findFirst()
                .ifPresent(k -> {
                    k.setDeletedAt(LocalDateTime.now());
                    k.setStatus("REVOKED");
                    apiKeyRepository.save(k);
                });
    }

    public record ApiKeyMaterial(String rawKey, Long id) {}
}
