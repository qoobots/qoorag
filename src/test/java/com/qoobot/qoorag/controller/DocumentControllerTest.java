package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.GlobalExceptionHandler;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.entity.IngestTask;
import com.qoobot.qoorag.repository.DocumentRepository;
import com.qoobot.qoorag.service.AuditService;
import com.qoobot.qoorag.service.ChatCacheService;
import com.qoobot.qoorag.service.IngestQueueService;
import com.qoobot.qoorag.service.IngestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DocumentController Web 测试：批量异步上传 + 任务查询（RAG-030） */
public class DocumentControllerTest {

    IngestQueueService ingestQueueService = mock(IngestQueueService.class);
    IngestService ingestService = mock(IngestService.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    AuditService auditService = mock(AuditService.class);
    ChatCacheService chatCacheService = mock(ChatCacheService.class);
    DocumentController controller = new DocumentController(
            ingestQueueService, ingestService, documentRepository, auditService, chatCacheService);
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

    private static IngestTask task(long id, String name, String status, int progress) {
        IngestTask t = new IngestTask();
        t.setId(id);
        t.setKbId(2L);
        t.setTenantId(7L);
        t.setFilename(name);
        t.setStatus(status);
        t.setStage(IngestTask.STAGE_EMBED);
        t.setProgress(progress);
        return t;
    }

    @Test
    void upload_multiple_files_returns_task_ids() throws Exception {
        when(ingestQueueService.enqueue(any(), eq(2L), eq(7L), any()))
                .thenReturn(task(100L, "a.txt", IngestTask.QUEUED, 0),
                            task(101L, "b.md", IngestTask.QUEUED, 0));

        MockMultipartFile f1 = new MockMultipartFile("files", "a.txt", "text/plain", "hello".getBytes());
        MockMultipartFile f2 = new MockMultipartFile("files", "b.md", "text/markdown", "# md".getBytes());

        mockMvc.perform(multipart("/api/kb/2/documents").file(f1).file(f2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.accepted").value(2))
                .andExpect(jsonPath("$.data.results[0].taskId").value(100))
                .andExpect(jsonPath("$.data.results[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.data.results[1].name").value("b.md"));

        verify(ingestQueueService, times(2)).enqueue(any(), eq(2L), eq(7L), any());
        verify(auditService, times(2)).log(eq("UPLOAD_DOCUMENT"), anyString(), anyString(), isNull(), anyString());
    }

    @Test
    void upload_with_unsupported_format_marks_failed_not_throw() throws Exception {
        MockMultipartFile good = new MockMultipartFile("files", "a.txt", "text/plain", "hello".getBytes());
        MockMultipartFile bad = new MockMultipartFile("files", "c.doc", "application/octet-stream", "x".getBytes());

        when(ingestQueueService.enqueue(any(), eq(2L), eq(7L), any()))
                .thenReturn(task(20L, "a.txt", IngestTask.QUEUED, 0));

        mockMvc.perform(multipart("/api/kb/2/documents").file(good).file(bad))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.results[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data.results[1].error").exists());

        verify(ingestQueueService, times(1)).enqueue(any(), eq(2L), eq(7L), any());
    }

    @Test
    void upload_empty_files_returns_fail() throws Exception {
        mockMvc.perform(multipart("/api/kb/2/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void list_ingest_tasks_returns_progress() throws Exception {
        when(ingestQueueService.listByKb(2L))
                .thenReturn(List.of(task(100L, "a.txt", IngestTask.RUNNING, 50),
                                    task(101L, "b.md", IngestTask.SUCCEEDED, 100)));

        mockMvc.perform(get("/api/kb/2/ingest-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].progress").value(50))
                .andExpect(jsonPath("$.data[1].status").value("SUCCEEDED"));
    }

    @Test
    void retry_failed_task_re_enqueues() throws Exception {
        IngestTask failed = task(100L, "a.txt", IngestTask.FAILED, 0);
        failed.setRetryCount(1);
        when(ingestQueueService.retry(2L, 100L)).thenReturn(failed);

        mockMvc.perform(post("/api/kb/2/ingest-tasks/100/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.retryCount").value(1));

        verify(auditService).log(eq("RETRY_INGEST_TASK"), eq("IngestTask"), eq("100"), isNull(), anyString());
    }
}
