package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.entity.KnowledgeBase;
import com.qoobot.qoorag.repository.ChunkRepository;
import com.qoobot.qoorag.repository.DocumentRepository;
import com.qoobot.qoorag.repository.KbPermissionRepository;
import com.qoobot.qoorag.repository.KnowledgeBaseRepository;
import com.qoobot.qoorag.repository.VectorDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** KnowledgeBaseService.create 入口的数据分级校验（#17）：非法分级抛 40001，空值默认 INTERNAL */
public class DataClassificationValidationTest {

    KnowledgeBaseRepository kbRepository = mock(KnowledgeBaseRepository.class);
    KbPermissionRepository permissionRepository = mock(KbPermissionRepository.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    ChunkRepository chunkRepository = mock(ChunkRepository.class);
    VectorDataRepository vectorDataRepository = mock(VectorDataRepository.class);

    KnowledgeBaseService kbService = new KnowledgeBaseService(
            kbRepository, permissionRepository, documentRepository, chunkRepository, vectorDataRepository);

    @BeforeEach
    void setUp() {
        SessionInfo info = new SessionInfo();
        info.tenantId = 7L;
        info.userId = 1L;
        SecurityContext.set(info);
        when(kbRepository.save(any(KnowledgeBase.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void create_valid_classification_succeeds() {
        KnowledgeBase kb = kbService.create("kb1", "d", "CONFIDENTIAL");
        assertEquals("CONFIDENTIAL", kb.getDataClassification());
    }

    @Test
    void create_blank_classification_defaults_to_internal() {
        KnowledgeBase kb = kbService.create("kb2", "d", null);
        assertEquals("INTERNAL", kb.getDataClassification());
    }

    @Test
    void create_invalid_classification_throws_40001() {
        BizException ex = assertThrows(BizException.class, () -> kbService.create("kb3", "d", "TOPSECRET"));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
    }
}
