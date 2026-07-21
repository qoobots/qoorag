package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.GlobalExceptionHandler;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.config.MetricsConfig;
import com.qoobot.qoorag.dto.RetrieveChunk;
import com.qoobot.qoorag.entity.QaTrace;
import com.qoobot.qoorag.repository.QaTraceRepository;
import com.qoobot.qoorag.service.ChatService;
import com.qoobot.qoorag.service.RetrieveService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ApiController Web 测试：retrieve / chat（API Key 通道，SecurityContext 由测试注入） */
public class ApiControllerTest {

    RetrieveService retrieveService = mock(RetrieveService.class);
    ChatService chatService = mock(ChatService.class);
    QaTraceRepository qaTraceRepository = mock(QaTraceRepository.class);
    MetricsConfig.RagMetrics ragMetrics = mock(MetricsConfig.RagMetrics.class);
    ApiController controller = new ApiController(qaTraceRepository, retrieveService, chatService, ragMetrics);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private SessionInfo ctx;

    @BeforeEach
    void setUp() {
        ctx = new SessionInfo();
        ctx.kbId = 2L;
        ctx.tenantId = 7L;
        ctx.isApiKey = true;
        SecurityContext.set(ctx);
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void retrieve_empty_query_returns_400() throws Exception {
        mockMvc.perform(post("/api/v1/retrieve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void retrieve_success_returns_chunks() throws Exception {
        when(retrieveService.retrieve("q", 5, 2L, 7L))
                .thenReturn(List.of(new RetrieveChunk(1L, 1L, 1L, 0, "content", 0.9)));

        mockMvc.perform(post("/api/v1/retrieve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"q\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.chunks").isArray())
                .andExpect(jsonPath("$.data.chunks[0].content").value("content"));

        verify(ragMetrics).recordRetrieve(anyLong(), eq(true));
    }

    @Test
    void chat_success_returns_answer_and_traces() throws Exception {
        when(retrieveService.retrieve(eq("q"), anyInt(), eq(2L), eq(7L)))
                .thenReturn(List.of(new RetrieveChunk(1L, 1L, 1L, 0, "ctx", 0.9)));

        Map<String, Object> llm = new HashMap<>();
        llm.put("answer", "A");
        llm.put("model", "qwen-plus");
        Map<String, Object> usage = new HashMap<>();
        usage.put("promptTokens", 10);
        usage.put("completionTokens", 5);
        llm.put("usage", usage);
        when(chatService.chat(eq("q"), anyList())).thenReturn(llm);
        when(qaTraceRepository.save(any(QaTrace.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"q\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answer").value("A"))
                .andExpect(jsonPath("$.data.usage.totalTokens").value(15));

        verify(ragMetrics).recordChat(anyLong());
        verify(qaTraceRepository).save(any(QaTrace.class));
    }
}
