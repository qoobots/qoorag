package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.GlobalExceptionHandler;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.entity.Document;
import com.qoobot.qoorag.repository.DocumentRepository;
import com.qoobot.qoorag.service.AuditService;
import com.qoobot.qoorag.service.IngestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DocumentController Web 测试：批量上传（多文件） */
public class DocumentControllerTest {

    IngestService ingestService = mock(IngestService.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    AuditService auditService = mock(AuditService.class);
    DocumentController controller = new DocumentController(ingestService, documentRepository, auditService);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        SessionInfo info = new SessionInfo();
        info.kbId = 2L;
        info.tenantId = 7L;
        info.roles = Set.of("知识管理员");
        info.isApiKey = false;
        SecurityContext.set(info);
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void upload_multiple_files_returns_summary() throws Exception {
        Document d1 = new Document();
        d1.setId(10L); d1.setName("a.txt"); d1.setStatus("COMPLETED");
        Document d2 = new Document();
        d2.setId(11L); d2.setName("b.md"); d2.setStatus("COMPLETED");
        when(ingestService.ingest(any(), eq(2L), eq(7L))).thenReturn(d1, d2);

        MockMultipartFile f1 = new MockMultipartFile("files", "a.txt", "text/plain", "hello".getBytes());
        MockMultipartFile f2 = new MockMultipartFile("files", "b.md", "text/markdown", "# md".getBytes());

        mockMvc.perform(multipart("/api/kb/2/documents").file(f1).file(f2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.success").value(2))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.results[0].name").value("a.txt"))
                .andExpect(jsonPath("$.data.results[0].id").value(10))
                .andExpect(jsonPath("$.data.results[1].name").value("b.md"));

        verify(ingestService, times(2)).ingest(any(), eq(2L), eq(7L));
        verify(auditService, times(2)).log(eq("UPLOAD_DOCUMENT"), anyString(), anyString(), isNull(), anyString());
    }

    @Test
    void upload_with_unsupported_format_marks_failed_not_throw() throws Exception {
        MockMultipartFile good = new MockMultipartFile("files", "a.txt", "text/plain", "hello".getBytes());
        MockMultipartFile bad = new MockMultipartFile("files", "c.doc", "application/octet-stream", "x".getBytes());

        Document d1 = new Document();
        d1.setId(20L); d1.setName("a.txt"); d1.setStatus("COMPLETED");
        when(ingestService.ingest(any(), eq(2L), eq(7L))).thenReturn(d1);

        mockMvc.perform(multipart("/api/kb/2/documents").file(good).file(bad))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.success").value(1))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.results[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data.results[1].error").exists());

        verify(ingestService, times(1)).ingest(any(), eq(2L), eq(7L));
    }

    @Test
    void upload_empty_files_returns_fail() throws Exception {
        mockMvc.perform(multipart("/api/kb/2/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }
}
