package com.qoobot.qoorag.service;

import com.qoobot.qoorag.config.BailianConfig;
import com.qoobot.qoorag.entity.ResourcePoolConfig;
import com.qoobot.qoorag.repository.ResourcePoolConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ResourcePoolLoaderTest {

    @Mock ResourcePoolConfigRepository repository;
    @Mock BailianConfig bailianConfig;
    @InjectMocks ResourcePoolLoader loader;

    private ResourcePoolConfig cfg(String v) {
        ResourcePoolConfig c = new ResourcePoolConfig();
        c.setConfigValue(v);
        return c;
    }

    @Test
    void run_overridesBailianConfigWhenDbHasValue() {
        when(repository.findByCategoryAndConfigKey("LLM", "chat_model")).thenReturn(Optional.of(cfg("qwen-max")));
        when(repository.findByCategoryAndConfigKey("LLM", "api_key")).thenReturn(Optional.of(cfg("sk-xyz")));
        when(repository.findByCategoryAndConfigKey("LLM", "base_url")).thenReturn(Optional.of(cfg("https://x")));
        when(repository.findByCategoryAndConfigKey("EMBEDDING", "embedding_model")).thenReturn(Optional.of(cfg("text-embedding-v3")));
        when(repository.findByCategoryAndConfigKey("EMBEDDING", "embedding_batch_size")).thenReturn(Optional.of(cfg("50")));

        loader.run(null);

        verify(bailianConfig).setChatModel("qwen-max");
        verify(bailianConfig).setApiKey("sk-xyz");
        verify(bailianConfig).setBaseUrl("https://x");
        verify(bailianConfig).setEmbeddingModel("text-embedding-v3");
        verify(bailianConfig).setEmbeddingBatchSize(50);
    }

    @Test
    void run_keepsYmlDefaultWhenDbEmpty() {
        when(repository.findByCategoryAndConfigKey(anyString(), anyString())).thenReturn(Optional.empty());
        loader.run(null);
        verify(bailianConfig, never()).setChatModel(any());
        verify(bailianConfig, never()).setApiKey(any());
    }

    @Test
    void run_degradesGracefullyOnException() {
        when(repository.findByCategoryAndConfigKey(anyString(), anyString()))
                .thenThrow(new RuntimeException("table missing"));
        assertDoesNotThrow(() -> loader.run(null));
    }
}
