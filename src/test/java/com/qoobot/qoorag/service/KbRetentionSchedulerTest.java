package com.qoobot.qoorag.service;

import com.qoobot.qoorag.entity.KnowledgeBase;
import com.qoobot.qoorag.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** KbRetentionScheduler 单元测试：保留期定时清理调度逻辑 */
@ExtendWith(MockitoExtension.class)
public class KbRetentionSchedulerTest {

    @Mock KnowledgeBaseRepository kbRepository;
    @Mock KnowledgeBaseService knowledgeBaseService;
    KbRetentionScheduler scheduler;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        scheduler = new KbRetentionScheduler(kbRepository, knowledgeBaseService);
    }

    @Test
    void purgeExpired_calls_purge_for_each_expired_kb() {
        KnowledgeBase kb1 = new KnowledgeBase();
        kb1.setId(10L);
        KnowledgeBase kb2 = new KnowledgeBase();
        kb2.setId(11L);
        when(kbRepository.findByDeletedAtIsNotNullAndDeletedAtBefore(any())).thenReturn(List.of(kb1, kb2));

        scheduler.purgeExpired();

        verify(knowledgeBaseService).purge(10L);
        verify(knowledgeBaseService).purge(11L);
    }

    @Test
    void purgeExpired_empty_noop() {
        when(kbRepository.findByDeletedAtIsNotNullAndDeletedAtBefore(any())).thenReturn(List.of());

        scheduler.purgeExpired();

        verify(knowledgeBaseService, never()).purge(anyLong());
    }
}
