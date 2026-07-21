package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.dto.ResourcePoolItem;
import com.qoobot.qoorag.entity.ResourcePoolConfig;
import com.qoobot.qoorag.repository.ResourcePoolConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ResourcePoolServiceTest {

    @Mock ResourcePoolConfigRepository repository;
    @Mock AuditService auditService;
    @InjectMocks ResourcePoolService service;

    @Test
    void listGrouped_masksSensitive() {
        ResourcePoolConfig apiKey = new ResourcePoolConfig();
        apiKey.setCategory("LLM"); apiKey.setConfigKey("api_key");
        apiKey.setConfigValue("sk-secret"); apiKey.setMasked(true);
        apiKey.setDescription("key");
        ResourcePoolConfig model = new ResourcePoolConfig();
        model.setCategory("LLM"); model.setConfigKey("chat_model");
        model.setConfigValue("qwen-plus"); model.setMasked(false);
        model.setDescription("model");
        when(repository.findAll()).thenReturn(List.of(apiKey, model));

        Map<String, List<ResourcePoolItem>> grouped = service.listGrouped();
        List<ResourcePoolItem> llm = grouped.get("LLM");
        assertEquals(2, llm.size());
        ResourcePoolItem masked = llm.stream().filter(i -> i.getConfigKey().equals("api_key")).findFirst().get();
        assertEquals(ResourcePoolService.MASK, masked.getConfigValue());
        assertTrue(masked.isMasked());
        ResourcePoolItem plain = llm.stream().filter(i -> i.getConfigKey().equals("chat_model")).findFirst().get();
        assertEquals("qwen-plus", plain.getConfigValue());
    }

    @Test
    void save_updatesExistingEffectiveValue() {
        ResourcePoolConfig existing = new ResourcePoolConfig();
        existing.setCategory("LLM"); existing.setConfigKey("chat_model");
        existing.setConfigValue("qwen-plus"); existing.setMasked(false);
        when(repository.findByCategoryAndConfigKey("LLM", "chat_model")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResourcePoolItem saved = service.save("LLM", "chat_model", "qwen-max", "model");
        assertEquals("qwen-max", saved.getConfigValue());
        assertEquals("qwen-max", existing.getConfigValue());
        verify(auditService).log(eq("UPDATE"), eq("RESOURCE_POOL"), eq("LLM:chat_model"), any(), any());
    }

    @Test
    void save_emptyValueKeepsOriginal() {
        ResourcePoolConfig existing = new ResourcePoolConfig();
        existing.setCategory("LLM"); existing.setConfigKey("chat_model");
        existing.setConfigValue("qwen-plus"); existing.setMasked(false);
        when(repository.findByCategoryAndConfigKey("LLM", "chat_model")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.save("LLM", "chat_model", "", "model");
        assertEquals("qwen-plus", existing.getConfigValue());
    }

    @Test
    void save_createsNewWhenAbsent() {
        when(repository.findByCategoryAndConfigKey("VECTOR", "type")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            ResourcePoolConfig e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        ResourcePoolItem saved = service.save("VECTOR", "type", "pgvector", "向量库类型");
        assertEquals("pgvector", saved.getConfigValue());
        verify(repository).save(any());
    }

    @Test
    void save_blankCategoryThrows() {
        BizException ex = assertThrows(BizException.class, () -> service.save("", "k", "v", "d"));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
    }
}
